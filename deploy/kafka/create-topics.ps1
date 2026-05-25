$topics = @(
  "post.created",
  "post.updated",
  "post.deleted",
  "comment.created",
  "interaction.changed",
  "notification.created",
  "audit.completed"
)

foreach ($topic in $topics) {
  docker compose exec kafka /opt/kafka/bin/kafka-topics.sh `
    --bootstrap-server localhost:9092 `
    --create `
    --if-not-exists `
    --topic $topic `
    --partitions 3 `
    --replication-factor 1
}

