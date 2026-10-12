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
    while request("GET", f"/api/users/{author['id']}")[0] != 200:
        if time.monotonic() >= deadline: raise AssertionError("Author Outbox/Kafka projection did not converge")
        time.sleep(1)
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
    result = {"post_id": post, "reader_ids": [reader["id"], other["id"]],
              "concurrent_requests_per_case": 8,
              "passed": ["real_same_origin_object_upload", "cross_user_upload_completion_denied", "unpublished_media_access_denied",
                         "real_author_projection", "cross_user_draft_read_write_denied", "forged_identity_denied",
                         "reader_admin_access_denied", "pending_content_hidden", "stale_draft_conflict",
                         "unearned_solution_hidden", "same_key_concurrency", "different_key_concurrency",
                         "idempotency_payload_and_user_binding", "single_score_award", "earned_solution_visible"]}
    assert_persisted(result, sql)
    return result
