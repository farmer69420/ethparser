-- HOLDERS
select count(*) from (
 select owner, sum(result) result
 from (
          select *
          from (
                   select deposit.vault                                   vault,
                          deposit.owner                                   owner,
                          deposit.d_amnt                                  d_amnt,
                          coalesce(withdraw.w_amnt, 0)                    w_amnt,
                          (deposit.d_amnt - coalesce(withdraw.w_amnt, 0)) result
                   from (select vault, owner, sum(usd_amount) d_amnt
                         from harvest_tx
                         where method_name = 'Deposit'
                         group by vault, owner) deposit
                            left join
                        (select vault, owner, sum(usd_amount) w_amnt
                         from harvest_tx
                         where method_name = 'Withdraw'
                         group by vault, owner) withdraw
                        ON deposit.owner = withdraw.owner
                            and deposit.vault = withdraw.vault
               ) t1
          where t1.result > 0
          order by t1.result desc
      ) t2
 group by owner
 order by result desc
) t3
where result > 1000000
;

-- USERS QUANTITY BY POOLS
select vault, count(owner) from (
                  select vault,
                         owner,
                         SUBSTRING_INDEX(MAX(CONCAT(block_date, '_', owner_balance_usd)), '_', -1) balance
                  from harvest_tx
                  group by vault, owner
              ) t
where balance > 10
group by vault;

-- ALL USERS QUANTITY
select count(owner) owners
from (
         select distinct owner
         from (
                  select owner
                  from harvest_tx
                  union all
                  select owner
                  from uni_tx
              ) t
     ) t2;

-- USERS QUANTITY ALL POOLS
select count(owner) owners
from (select owner,
             SUBSTRING_INDEX(MAX(CONCAT(block_date, '_', owner_balance_usd)), '_', -1) balance
      from harvest_tx
      where vault not in ('PS', 'PS_V0', 'UNI_LP_USDC_FARM', 'UNI_LP_WETH_FARM', 'UNI_LP_WBTC_BADGER','UNI_LP_GRAIN_FARM')
      group by owner
     ) t
where balance > 10;

-- USERS QUANTITY WITH POSITIVE BALANCE FOR ALL POOLS
select count(owner) owners
from (select owner,
                      SUBSTRING_INDEX(MAX(CONCAT(block_date, '_', owner_balance_usd)), '_', -1) balance
               from harvest_tx
               where vault not in ('PS', 'PS_V0')
               group by owner
              ) t
    where balance > 10;

-- USERS QUANTITY FARM HOLDERS
select count(owner) from (
                             select owner,
                                    SUBSTRING_INDEX(MAX(CONCAT(block_date, '_', owner_balance_usd)), '_', -1) balance
                             from uni_tx
                             group by owner
                         ) t
where balance > 10;


-- WALLET HISTORY---------------------
select
       round(amount, 0) FARM,
       type,
       round(other_amount, 0) USDC,
       round(last_price, 0) for_price,
       FROM_UNIXTIME(block_date) date,
       hash
from uni_tx
where owner = '0xc54bd1f466f2f4f36de59f4024e86885386d6f1b'
order by block_date desc;

-- WHO SELL FARM---------------------
# select buy.owner, sum(buy.amount) sum_buy, sum(sell.amount) sum_sell, sum(sell.amount - coalesce(buy.amount, 0)) sum
select sell_owner owner, sum_sell, sum_buy, sum(sum_sell - coalesce(sum_buy, 0)) sum from (
                  select *
                  from
                           (select owner sell_owner, sum(amount) sum_sell
                            from uni_tx
                            where type = 'SELL'
#                               and block_date > 1606521600
                            group by owner) sell
                               left join
                               (select owner buy_owner, sum(amount) sum_buy
                                from uni_tx
                                where type = 'BUY'
#                                   and block_date > 1606521600
                                group by owner) buy on sell.sell_owner = buy.buy_owner

              ) t group by sell_owner, sum_sell, sum_buy
order by sum desc;

-- income per day ---------------------
select avg(ps_income) from (
                               select FROM_UNIXTIME(FLOOR(MIN(block_date) / 86400) * 86400) date,
                                      round(sum((share_change_usd / 0.7) * 0.3), 0)         ps_income
                               from hard_work
                               GROUP BY FLOOR(block_date / 86400)
                               order by date desc
                           ) t;


-- last hard works ---------------------
select
    FROM_UNIXTIME(block_date) date,
#        block,
#        block_date,
       round(share_change_usd, 0) vault_income_usd,
       round(farm_buyback, 0) ps_income_farm,
       vault,
       id
from hard_work
# where vault in ('UNI_BAC_DAI','UNI_DAI_BAS','SUSHI_MIC_USDT','SUSHI_MIS_USDT' )
# and share_change_usd != 0
order by block_date desc;

-- UNIQUE USERS ------------
select count(owner) from (
                             select owner
                             from harvest_tx
                             group by owner
                         ) t;

-- rewards order by date
select
    FROM_UNIXTIME(block_date) date,
    round(reward, 0) vault_income,
    vault
from rewards
order by block_date desc;

-- last rewards group by vaults
select FROM_UNIXTIME(max(block_date))                                             date,
       vault                                                                vault,
       SUBSTRING_INDEX(MAX(CONCAT(block_date, '_', reward)), '_', -1)     reward,
       SUBSTRING_INDEX(MAX(CONCAT(block_date, '_', period_finish)), '_', -1)     period_finish

from rewards
where period_finish > (UNIX_TIMESTAMP() - 604800)
group by vault
order by date desc;

-- TOKEN BALANCE
select  coalesce(buy, 0) - coalesce(sell, 0) sum from
(select sum(value) buy from transfers
where block_date <= 99999999999
  and recipient = :address
    ) buys
    left join
(select sum(value) sell from transfers
where block_date <= 99999999999
  and owner = :address
    ) sells on 1=1;

-- assets under control
select t.owner, count(t.vault) assets from (select owner, vault,
                                              cast(SUBSTRING_INDEX(MAX(CONCAT(block_date, '_', owner_balance_usd)), '_', -1) as DOUBLE PRECISION) b
                                       from harvest_tx where owner_balance_usd is not null and network = 'bsc'
group by owner,vault) t
where b > 10
group by t.owner
order by assets desc
;

-- lp to token links
select
       link.id,
       token_contract.network,
       token_contract.name,
       token_contract.address,
       lp_contract.name,
       link.block_start
from eth_token_to_uni_pair link
         left join eth_tokens token on token.id = link.token_id
         left join eth_contracts token_contract on token_contract.id = token.contract
         left join eth_uni_pairs lp on lp.id = link.uni_pair_id
         left join eth_contracts lp_contract on lp_contract.id = lp.contract
-- where  lower(token_contract.address) =
--        lower('0xedb0414627e6f1e3f082de65cd4f9c693d78cca9')
order by token_contract.name, link.block_start
;

-- lps
select lp_contract.name,
       lp_contract.address,
       eth_uni_pairs.token0_id,
       eth_uni_pairs.token1_id,
       et.symbol
from eth_uni_pairs
join eth_contracts lp_contract on eth_uni_pairs.contract = lp_contract.id
left join eth_tokens et on eth_uni_pairs.key_token = et.id
-- where lower(lp_contract.address) = lower('0x4a9596e5d2f9bef50e4de092ad7181ae3c40353e')
;

-- pools
select ec.name, reward_ec.name
from eth_pools pool
left join eth_contracts ec on ec.id = pool.contract
left join eth_contracts reward_ec on reward_ec.id = pool.reward_token;
