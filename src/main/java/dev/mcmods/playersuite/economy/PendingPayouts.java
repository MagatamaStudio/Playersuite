package dev.mcmods.playersuite.economy;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 离线待领取收入（全局 {@link SavedData}）：店主/收件人不在服务端时，
 * 属于他的金币先进这里，等他上线在界面里一键领取。
 *
 * <p>不直接改离线玩家的存档数据，避免与原版 playerdata 读写冲突。
 */
public final class PendingPayouts extends SavedData {
    private static final String NAME = "playersuite_pending_payouts";
    private static final String TAG_ENTRIES = "Entries";
    private static final String TAG_OWNER = "Owner";
    private static final String TAG_AMOUNT = "Amount";
    private static final String TAG_REASON = "Reason";
    private static final String TAG_TIME = "Time";

    /** 单个 owner 最多保留多少条待领取记录（超出后并入最后一条，防无界增长）。 */
    private static final int MAX_ENTRIES_PER_OWNER = 64;

    public static final SavedData.Factory<PendingPayouts> FACTORY =
            new SavedData.Factory<>(PendingPayouts::new, PendingPayouts::load);

    /** 一条待领取记录。 */
    public record Entry(long amount, String reason, long time) {
    }

    private final Map<UUID, List<Entry>> entries = new HashMap<>();

    public PendingPayouts() {
    }

    private static PendingPayouts load(CompoundTag tag, HolderLookup.Provider provider) {
        PendingPayouts data = new PendingPayouts();
        if (tag.contains(TAG_ENTRIES, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                UUID owner = entry.getUUID(TAG_OWNER);
                data.entries.computeIfAbsent(owner, k -> new ArrayList<>())
                        .add(new Entry(entry.getLong(TAG_AMOUNT), entry.getString(TAG_REASON), entry.getLong(TAG_TIME)));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, List<Entry>> e : entries.entrySet()) {
            for (Entry entry : e.getValue()) {
                CompoundTag one = new CompoundTag();
                one.putUUID(TAG_OWNER, e.getKey());
                one.putLong(TAG_AMOUNT, entry.amount());
                one.putString(TAG_REASON, entry.reason() == null ? "" : entry.reason());
                one.putLong(TAG_TIME, entry.time());
                list.add(one);
            }
        }
        tag.put(TAG_ENTRIES, list);
        return tag;
    }

    // ------------------------------------------------------------------ 访问

    public static PendingPayouts of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** 记一笔待领取（同一 owner 超过 {@link #MAX_ENTRIES_PER_OWNER} 条后并入最后一条）。 */
    public void add(UUID owner, long amount, String reason) {
        if (amount <= 0L || owner == null) {
            return;
        }
        List<Entry> list = entries.computeIfAbsent(owner, k -> new ArrayList<>());
        if (list.size() >= MAX_ENTRIES_PER_OWNER) {
            // 离线店主每成交一笔都会调到这里；不封顶的话长期不登录的玩家会累积上万条，
            // total()/count() 线性求和、save() 全表序列化，内存与存档都会膨胀。
            Entry last = list.get(list.size() - 1);
            list.set(list.size() - 1,
                    new Entry(last.amount() + amount, last.reason(), System.currentTimeMillis()));
        } else {
            list.add(new Entry(amount, reason, System.currentTimeMillis()));
        }
        setDirty();
    }

    /**
     * 领取至多 {@code max} 金币，返回实际领取额；未领完的部分保留（供钱包余额上限场景使用）。
     */
    public long claimUpTo(UUID owner, long max) {
        if (max <= 0L) {
            return 0L;
        }
        List<Entry> list = entries.get(owner);
        if (list == null || list.isEmpty()) {
            return 0L;
        }
        long claimed = 0L;
        while (!list.isEmpty() && claimed < max) {
            Entry first = list.get(0);
            long take = Math.min(first.amount(), max - claimed);
            claimed += take;
            if (take >= first.amount()) {
                list.remove(0);
            } else {
                list.set(0, new Entry(first.amount() - take, first.reason(), first.time()));
            }
        }
        if (list.isEmpty()) {
            entries.remove(owner);
        }
        setDirty();
        return claimed;
    }

    /** 某人当前待领取总额。 */
    public long total(UUID owner) {
        List<Entry> list = entries.get(owner);
        if (list == null) {
            return 0L;
        }
        long sum = 0L;
        for (Entry e : list) {
            sum += e.amount();
        }
        return sum;
    }

    /** 待领取笔数（用于界面提示）。 */
    public int count(UUID owner) {
        List<Entry> list = entries.get(owner);
        return list == null ? 0 : list.size();
    }

    /** 一次性领完，返回实际领取总额。 */
    public long claimAll(UUID owner) {
        List<Entry> list = entries.remove(owner);
        if (list == null || list.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (Entry e : list) {
            sum += e.amount();
        }
        setDirty();
        return sum;
    }

    /** 管理员清空某人待领取（例如误操作回滚）。 */
    public void clear(UUID owner) {
        if (entries.remove(owner) != null) {
            setDirty();
        }
    }
}
