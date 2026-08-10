-- 升级前若已有并存投票，保留最早创建的一条，其余投票安全取消。
UPDATE governance_votes
   SET status = 'CANCELLED',
       settled_at = COALESCE(settled_at, CAST(unixepoch('subsec') * 1000 AS INTEGER)),
       cancelled_reason = COALESCE(cancelled_reason, '升级治理约束时取消同镇重复开放投票')
 WHERE vote_id IN (
       SELECT vote_id
         FROM (
               SELECT vote_id,
                      ROW_NUMBER() OVER (
                          PARTITION BY town_id ORDER BY created_at, vote_id
                      ) AS open_order
                 FROM governance_votes
                WHERE status = 'OPEN'
              ) ranked_votes
        WHERE open_order > 1
       );

CREATE UNIQUE INDEX uq_governance_vote_open_town
    ON governance_votes (open_town_id);
