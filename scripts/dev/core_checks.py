"""Exercise the real loopback core API and verify persistent business invariants.

Creates labelled local test accounts/content. Does not delete existing data.
"""
from concurrent.futures import ThreadPoolExecutor
import base64
import json
import threading
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:5512"
PASSWORD = "LocalCore2026!"
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def request(method, path, token=None, data=None, key=None, extra=None):
    headers = {"Content-Type": "application/json"}
    if token: headers["Authorization"] = "Bearer " + token
    if method in ("POST", "PUT", "DELETE"): headers["Idempotency-Key"] = key or str(uuid.uuid4())
    headers.update(extra or {})
    req = urllib.request.Request(BASE + path, method=method, headers=headers,
                                 data=json.dumps(data).encode() if data is not None else None)
    try:
        with HTTP.open(req, timeout=20) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        if error.code >= 500:
            # Keep the failing operation, but never include bearer headers, request bodies or signed queries.
            raise AssertionError(f"Core API {method} {path.split('?', 1)[0]} returned HTTP {error.code}") from None
        return error.code, json.loads(error.read())


def ok(result):
    status, body = result
    if status != 200:
        raise AssertionError(f"Expected HTTP 200, received {status}: {body.get('code')}")
    return body["data"]


def assert_status(result, allowed):
    if result[0] not in allowed:
        raise AssertionError(f"Expected HTTP {allowed}, received {result[0]}")


def register(label):
    email = f"local-{label}-{uuid.uuid4().hex[:12]}@example.test"
    data = ok(request("POST", "/api/auth/register", data={"email": email, "password": PASSWORD,
                                                          "displayName": "Local " + label}))
    return {"email": email, "id": int(data["user"]["userId"]), "token": data["accessToken"]}


def concurrent_answers(user, post, keys):
    barrier = threading.Barrier(len(keys))
    def send(key):
        barrier.wait(timeout=20)
        return ok(request("POST", f"/api/deduction/cases/{post}/answers", user["token"],
                          {"answer": "clock"}, key))
    with ThreadPoolExecutor(max_workers=len(keys)) as pool:
        results = list(pool.map(send, keys))
    if any(not r["correct"] or r["points"] != 20 for r in results):
        raise AssertionError("Concurrent answers returned inconsistent business results")


def assert_persisted(checks, sql):
    post = int(checks["post_id"])
    for user in checks["reader_ids"]:
        user = int(user)
        rows = sql(f"SELECT COUNT(*),SUM(points) FROM dc_content.deduction_attempt WHERE post_id={post} AND user_id={user};")
        if rows != "1\t20": raise AssertionError(f"Attempt invariant broken: {rows}")
        scores = sql(f"SELECT correct_count,total_points FROM dc_content.deduction_score WHERE user_id={user};")
        if scores != "1\t20": raise AssertionError(f"Score invariant broken: {scores}")
    solved = sql(f"SELECT solved_count FROM dc_content.post WHERE id={post};")
    if solved != "2": raise AssertionError(f"Solved count invariant broken: {solved}")
    for notice in checks.get("notifications", []):
        count = sql("SELECT COUNT(*) FROM dc_notification.notification "
                    f"WHERE id={int(notice['id'])} AND receiver_id={int(notice['receiver_id'])} "
                    f"AND actor_id={int(notice['actor_id'])} AND read_flag={int(notice['read'])};")
        if count != "1": raise AssertionError("Notification ownership/read state changed across restart")


def check_notification_permissions(owner, reader, other, sql):
    """Real Gateway -> Identity Notification API, with Kafka fixtures and database side-effect checks."""
    def follow(actor, receiver):
        result = ok(request("POST", f"/api/users/{receiver['id']}/follow", actor["token"]))
        if not result["followed"]: raise AssertionError("Fixture follow was not persisted")

    def notices(user, actors):
        deadline = time.monotonic() + 30
        while True:
            rows = ok(request("GET", "/api/notifications?type=FOLLOW&size=50", user["token"]))
            if len(rows) == len(actors) and {int(row["actorId"]) for row in rows} == actors:
                return {int(row["actorId"]): row for row in rows}
            if time.monotonic() >= deadline: raise AssertionError("Notification fixtures did not converge through Kafka")
            time.sleep(1)

    def unread(user):
        return ok(request("GET", "/api/notifications/unread-count", user["token"]))

    follow(reader, owner); follow(other, owner); follow(owner, reader)
    owned = notices(owner, {reader["id"], other["id"]})
    readers = notices(reader, {owner["id"]})
    private_id = int(owned[reader["id"]]["id"])
    reader_id = int(readers[owner["id"]]["id"])
    spoof = {"X-User-Id": str(owner["id"]), "X-Roles": "ADMIN"}
    for method, path in (("GET", "/api/notifications"), ("GET", "/api/notifications/unread-count"),
                         ("POST", f"/api/notifications/{private_id}/read"), ("POST", "/api/notifications/read-all")):
        assert_status(request(method, path, extra=spoof), {401})
    rows = ok(request("GET", "/api/notifications?type=FOLLOW", reader["token"], extra=spoof))
    if [int(row["id"]) for row in rows] != [reader_id] or unread(owner) != 2 or unread(reader) != 1:
        raise AssertionError("Forged identity exposed another receiver's notifications")

    rejected_key = str(uuid.uuid4())
    assert_status(request("POST", f"/api/notifications/{private_id}/read", reader["token"],
                          key=rejected_key, extra=spoof), {404})
    # An admin has its own private inbox; the role alone must not grant access to another receiver's IDs.
    assert_status(request("POST", f"/api/notifications/{reader_id}/read", owner["token"]), {404})
    if sql(f"SELECT COUNT(*) FROM dc_notification.notification_idempotency_record WHERE idempotency_key='{rejected_key}';") != "0":
        raise AssertionError("Rejected notification mutation consumed its idempotency key")
    if unread(owner) != 2 or unread(reader) != 1:
        raise AssertionError("Rejected notification mutation changed unread state")

    all_key = str(uuid.uuid4())
    if ok(request("POST", "/api/notifications/read-all", reader["token"], key=all_key, extra=spoof))["updated"] != 1:
        raise AssertionError("Mark-all did not affect exactly the caller's inbox")
    if unread(owner) != 2 or unread(reader) != 0:
        raise AssertionError("Mark-all crossed receiver boundaries")
    follow(other, reader)
    readers = notices(reader, {owner["id"], other["id"]})
    if ok(request("POST", "/api/notifications/read-all", reader["token"], key=all_key))["updated"] != 1 or unread(reader) != 1:
        raise AssertionError("A duplicate mark-all consumed a notification arriving later")
    assert_status(request("POST", "/api/notifications/read-all", owner["token"], key=all_key), {409})
    assert_status(request("POST", f"/api/notifications/{reader_id}/read", reader["token"], key=all_key), {409})

    first = ok(request("POST", f"/api/notifications/{private_id}/read", owner["token"], key=rejected_key))
    again = ok(request("POST", f"/api/notifications/{private_id}/read", owner["token"], key=rejected_key))
    if first["duplicateRequest"] or not again["duplicateRequest"]:
        raise AssertionError("Owner retry did not preserve notification idempotency")
    assert_status(request("POST", f"/api/notifications/{reader_id}/read", reader["token"], key=rejected_key), {409})
    if unread(owner) != 1 or unread(reader) != 1:
        raise AssertionError("Idempotency conflicts changed another notification")
    return [{"id": int(row["id"]), "receiver_id": receiver["id"], "actor_id": actor,
             "read": actor == read_actor}
            for receiver, values, read_actor in ((owner, owned, reader["id"]), (reader, readers, owner["id"]))
            for actor, row in values.items()]


def check_core(sql):
    # Desktop port forwarding can become reachable after container health is green.
    deadline = time.monotonic() + 30
    while True:
        try:
            with HTTP.open(BASE + "/", timeout=5) as response:
                if response.status == 200: break
        except (urllib.error.URLError, TimeoutError):
            pass
        if time.monotonic() >= deadline: raise AssertionError("Core Web port is not reachable from the host")
        time.sleep(1)
    author, reader, other = register("author"), register("reader"), register("other")
    png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3ioAAAAASUVORK5CYII=")
    upload = ok(request("POST", "/api/files/intents", author["token"],
                        {"filename": "local-core.png", "contentType": "image/png", "size": len(png), "width": 1, "height": 1}))
    if not upload["uploadUrl"].startswith(BASE + "/devcircle/"):
        raise AssertionError("Presigned upload must use the same loopback origin")
    with HTTP.open(urllib.request.Request(upload["uploadUrl"], method="PUT", data=png,
                                         headers={"Content-Type": "image/png"}), timeout=20) as response:
        if response.status != 200: raise AssertionError("Real object upload failed")
    media_id = int(upload["mediaId"])
    assert_status(request("POST", f"/api/files/{media_id}/complete", reader["token"]), {403, 404})
    ok(request("POST", f"/api/files/{media_id}/complete", author["token"]))
    assert_status(request("GET", f"/api/files/{media_id}/access"), {403, 404})
    # Bootstrap only the new account in this dedicated local database, then obtain a fresh JWT.
    sql(f"INSERT IGNORE INTO dc_auth.user_role(user_id,role_id) SELECT {author['id']},id FROM dc_auth.role WHERE code='ADMIN';")
    admin = ok(request("POST", "/api/auth/login", data={"email": author["email"], "password": PASSWORD}))["accessToken"]
    deadline = time.monotonic() + 30
    while any(request("GET", f"/api/users/{user['id']}")[0] != 200 for user in (author, reader, other)):
        if time.monotonic() >= deadline: raise AssertionError("Author Outbox/Kafka projection did not converge")
        time.sleep(1)
    notifications = check_notification_permissions({**author, "token": admin}, reader, other, sql)
    draft = {"title": "Local core clock " + uuid.uuid4().hex[:8],
             "content": "The clock stopped seven minutes before the witness arrived. Follow the timeline.",
             "type": "TEXT", "tags": ["local-core"], "theme": "DEDUCTION", "contentKind": "CASE_FILE",
             "difficulty": "EASY", "solvable": True, "clues": "The clock was changed.",
             "suspects": "The witness", "timeline": "Midnight", "question": "What changed?",
             "answerKeywords": "clock", "solution": "The clock was altered to conceal the timeline.",
             "mediaIds": [], "version": 0}
    created = ok(request("POST", "/api/posts/drafts", author["token"], draft)); post = int(created["id"])
    for identity in (reader, other):
        assert_status(request("GET", f"/api/posts/drafts/{post}", identity["token"]), {403, 404})
        assert_status(request("PUT", f"/api/posts/drafts/{post}", identity["token"], draft), {403, 404})
    assert_status(request("GET", f"/api/posts/drafts/{post}", reader["token"],
                          extra={"X-User-Id": str(author["id"]), "X-User-Roles": "ADMIN"}), {403, 404})
    assert_status(request("GET", "/api/admin/moderation/tasks?status=PENDING&page=1&size=20", reader["token"]), {403})
    assert_status(request("GET", f"/api/posts/{post}"), {404})
    # Stale writer must not overwrite a newer draft.
    edited = ok(request("PUT", f"/api/posts/drafts/{post}", author["token"], {**draft, "version": created["version"]}))
    assert_status(request("PUT", f"/api/posts/drafts/{post}", author["token"], {**draft, "content": "stale overwrite", "version": created["version"]}), {409})
    ok(request("POST", f"/api/posts/drafts/{post}/submit?version={edited['version']}", author["token"]))
    tasks = ok(request("GET", "/api/admin/moderation/tasks?status=PENDING&page=1&size=100", admin))
    task = next(t for t in tasks if int(t["postId"]) == post)
    ok(request("POST", f"/api/admin/moderation/tasks/{task['taskId']}/approve", admin,
               {"reason": "Local core invariant validation; original synthetic test content."}))
    public = ok(request("GET", f"/api/posts/{post}"))
    if public.get("solution") is not None or "answerKeywords" in public:
        raise AssertionError("Public response leaks protected answer information")
    shared_key = str(uuid.uuid4())
    concurrent_answers(reader, post, [shared_key] * 8)
    concurrent_answers(other, post, [str(uuid.uuid4()) for _ in range(8)])
    assert_status(request("POST", f"/api/deduction/cases/{post}/answers", reader["token"],
                          {"answer": "different payload"}, shared_key), {409})
    assert_status(request("POST", f"/api/deduction/cases/{post}/answers", other["token"],
                          {"answer": "clock"}, shared_key), {409})
    private = ok(request("GET", f"/api/posts/{post}", reader["token"]))
    if private.get("solution") != draft["solution"]: raise AssertionError("Solved reader cannot access solution")
    result = {"post_id": post, "reader_ids": [reader["id"], other["id"]], "notifications": notifications,
              "concurrent_requests_per_case": 8,
              "passed": ["real_same_origin_object_upload", "cross_user_upload_completion_denied", "unpublished_media_access_denied",
                         "real_author_projection", "cross_user_draft_read_write_denied", "forged_identity_denied",
                         "reader_admin_access_denied", "pending_content_hidden", "stale_draft_conflict",
                         "unearned_solution_hidden", "same_key_concurrency", "different_key_concurrency",
                         "idempotency_payload_and_user_binding", "single_score_award", "earned_solution_visible",
                         "notification_anonymous_and_cross_receiver_denied", "notification_forged_identity_denied",
                         "notification_rejected_mutation_has_no_claim", "notification_mark_all_owner_only",
                         "notification_retry_preserves_later_arrivals", "notification_key_user_and_operation_binding"]}
    assert_persisted(result, sql)
    return result
