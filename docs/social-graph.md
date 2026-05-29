# 社交图谱

当前版本使用 MySQL + Redis 实现轻量社交图谱，不引入 Neo4j、JanusGraph 或其他图数据库。

## 关系存储

- `user_follow`: 关注关系，`(follower_id, followee_id)` 唯一索引保证关注幂等。
- `user_profile`: 维护粉丝数、关注数、发帖数等计数。

## Redis 缓存

- `user:{id}:followees`: 用户关注集合。
- `user:{id}:graph-features`: 推荐侧可复用的图谱特征。

## 二跳推荐

二跳推荐通过 MySQL join 找到“关注了同一批作者的人”，按共同关注数量排序。该信号会进入推荐模型：

- 关注作者加权。
- 共同关注加权。
- 重复曝光惩罚。

这套设计部署成本低，适合开源演示和继续迭代。
