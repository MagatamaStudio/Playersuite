package dev.mcmods.playersuite.market;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.config.SuiteConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 官方市场的全局状态（SavedData：{@code playersuite_market}）。
 *
 * <p>目录本体在 {@link MarketCatalog}（只读、来自配置，不落盘）；这里只存四类会变的数：
 * <ol>
 *     <li>每条目的<b>剩余库存</b>（单位 = 份）+ 补货基准上限 + 上次补货时间；</li>
 *     <li>每条目的 <b>OP 改价覆盖值</b>（买价/卖价，{@link #NO_OVERRIDE} = 用配置值）；</li>
 *     <li>每条目的<b>临时禁用标记</b>；</li>
 *     <li>每玩家<b>当日已购件数</b>（自然日，见下）。</li>
 * </ol>
 *
 * <p><b>自然日界（硬编码常量）</b>：{@link #DAY_TICKS} = 24000 tick，而原版 dayTime 的 0 点
 * 正好是清晨 06:00，所以「一天」= {@code dayTime / 24000}，限购日界固定在世界时间
 * <b>06:00</b>（用游戏日而非真实时钟，避免玩家改系统时间刷限购）。
 *
 * <p><b>自动补货</b>：间隔 = {@code SuiteConfig.marketRestockHours()} × {@link #HOUR_TICKS}
 * （1 游戏小时 = 1000 tick）；配 0 表示不随时间自动回满，只在「首次见到该条目 /
 * 配置库存上限变化 / OP 一键补货」时回满。
 *
 * <p>所有写操作都会 {@code setDirty()}；读旧档缺键全部取默认值，不会崩。
 */
public final class MarketState extends SavedData {
    public static final String NAME = "playersuite_market";

    /** 一天的 tick 数；dayTime 的 0 点即 06:00，故限购日界 = 06:00。 */
    public static final long DAY_TICKS = 24000L;
    /** 一个小时的游戏 tick 数。 */
    public static final long HOUR_TICKS = 1000L;
    /** 价格覆盖值的「未设置」哨兵。 */
    public static final long NO_OVERRIDE = -1L;

    private static final String TAG_ENTRIES = "Entries";
    private static final String TAG_ID = "Id";
    private static final String TAG_STOCK = "Stock";
    private static final String TAG_MAX = "Max";
    private static final String TAG_RESTOCK_AT = "RestockAt";
    private static final String TAG_BUY_OVER = "BuyOverride";
    private static final String TAG_SELL_OVER = "SellOverride";
    private static final String TAG_DISABLED = "Disabled";
    private static final String TAG_PLAYERS = "Players";
    private static final String TAG_UUID = "Uuid";
    private static final String TAG_DAY = "Day";
    private static final String TAG_BOUGHT = "Bought";

    public static final SavedData.Factory<MarketState> FACTORY =
            new SavedData.Factory<>(MarketState::new, MarketState::load);

    /** 单条目状态（可变，按条目 id 索引）。 */
    public static final class EntryState {
        private final String id;
        private int stock;
        private int max;
        private long restockAt;
        private long buyOverride = NO_OVERRIDE;
        private long sellOverride = NO_OVERRIDE;
        private boolean disabled;

        EntryState(String id, int stock, int max, long restockAt) {
            this.id = id;
            this.stock = stock;
            this.max = max;
            this.restockAt = restockAt;
        }

        public String id() {
            return id;
        }

        /** 剩余库存（份）；负数 = 不限。 */
        public int stock() {
            return stock;
        }

        /** 补货基准（份）；负数 = 不限。 */
        public int max() {
            return max;
        }

        public long restockAt() {
            return restockAt;
        }

        public long buyOverride() {
            return buyOverride;
        }

        public long sellOverride() {
            return sellOverride;
        }

        public boolean disabled() {
            return disabled;
        }
    }

    private final Map<String, EntryState> entries = new HashMap<>();
    private final Map<UUID, DayBought> players = new HashMap<>();

    /** 玩家当日已购件数。 */
    private static final class DayBought {
        long day;
        int items;

        DayBought(long day, int items) {
            this.day = day;
            this.items = items;
        }
    }

    // ---------------------------------------------------------------- 访问

    public static MarketState of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** 主世界 dayTime；拿不到返回 0，绝不抛异常。 */
    public static long dayTime(MinecraftServer server) {
        try {
            ServerLevel level = server == null ? null : server.overworld();
            return level == null ? 0L : Math.max(0L, level.getDayTime());
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** 当前限购日序号（日界 06:00）。 */
    public static long dayIndex(MinecraftServer server) {
        return dayTime(server) / DAY_TICKS;
    }

    /** 自动补货间隔（tick）；0 = 不随时间自动补货。 */
    public static long restockTicks() {
        try {
            return Math.max(0, SuiteConfig.marketRestockHours()) * HOUR_TICKS;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 库存限制是否启用。 */
    public static boolean stockEnabled() {
        try {
            return SuiteConfig.marketStockEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    /** 只读条目状态（不建记录）。 */
    public EntryState peek(String id) {
        return id == null ? null : entries.get(id);
    }

    /**
     * 取条目状态，必要时创建 / 重新回满。
     *
     * @param entry   目录条目（库存上限、是否不限来自它）
     * @param now     当前 dayTime
     * @param byTime  是否允许按时间自动回满（{@code restockTicks() > 0}）
     */
    public EntryState stateFor(MarketCatalog.Entry entry, long now, boolean byTime) {
        int max = entry.unlimitedStock() ? -1 : Math.max(0, entry.stock());
        EntryState state = entries.get(entry.id());
        if (state == null) {
            state = new EntryState(entry.id(), max, max, now);
            entries.put(entry.id(), state);
            setDirty();
            return state;
        }
        boolean changed = false;
        if (state.max != max) {
            // 配置改了库存上限（含改成不限）：以配置为准重新回满
            state.max = max;
            state.stock = max;
            state.restockAt = now;
            changed = true;
        } else if (max >= 0 && byTime && now - state.restockAt >= restockTicks()) {
            state.stock = max;
            state.restockAt = now;
            changed = true;
        }
        if (changed) {
            setDirty();
        }
        return state;
    }

    /** 全部条目状态（id 排序快照，统计用）。 */
    public List<EntryState> all() {
        List<EntryState> out = new ArrayList<>(entries.values());
        out.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return out;
    }

    public int trackedEntries() {
        return entries.size();
    }

    /** 界面用剩余库存（-1 = 不限）。 */
    public int displayStock(MarketCatalog.Entry entry, EntryState state) {
        if (!stockEnabled() || entry.unlimitedStock()) {
            return -1;
        }
        if (state == null) {
            return Math.max(0, entry.stock());
        }
        return state.stock();
    }

    /** 生效买价（OP 覆盖优先）。 */
    public long buyPrice(MarketCatalog.Entry entry, EntryState state) {
        long over = state == null ? NO_OVERRIDE : state.buyOverride();
        return over >= 0L ? over : Math.max(0L, entry.buyPrice());
    }

    /** 生效卖价（OP 覆盖优先）。 */
    public long sellPrice(MarketCatalog.Entry entry, EntryState state) {
        long over = state == null ? NO_OVERRIDE : state.sellOverride();
        return over >= 0L ? over : Math.max(0L, entry.sellPrice());
    }

    // ---------------------------------------------------------------- 库存写入

    /** 扣库存（单位 = 份）；不足返回 false。不限库存直接成功。 */
    public boolean takeStock(MarketCatalog.Entry entry, EntryState state, int units) {
        if (units <= 0) {
            return true;
        }
        if (!stockEnabled() || entry.unlimitedStock() || state == null) {
            return true;
        }
        if (state.stock < 0) {
            // OP 用 /market stock <id> -1 设成「不限」：负库存是哨兵值，不能拿去和 units 比较，
            // 否则 units > -1 恒成立 → 该商品从此永远提示「无库存」。
            return true;
        }
        if (units > state.stock) {
            return false;
        }
        state.stock = state.stock - units;
        setDirty();
        return true;
    }

    /** 回滚库存（发货失败时；单位 = 份）。 */
    public void restoreStock(MarketCatalog.Entry entry, EntryState state, int units) {
        if (!stockEnabled() || entry.unlimitedStock() || state == null || units <= 0) {
            return;
        }
        if (state.max >= 0) {
            state.stock = (int) Math.min((long) state.max, (long) state.stock + units);
        } else {
            state.stock = state.stock + units;
        }
        setDirty();
    }

    /** OP 同时设置剩余库存与补货基准（-1 = 不限，夹到 [-1, 999999]）。 */
    public void setStockMax(EntryState state, int stock) {
        if (state == null) {
            return;
        }
        state.stock = clampStock(stock);
        state.max = state.stock;
        setDirty();
    }

    /** 只改剩余库存，不动补货基准。 */
    public void setStock(EntryState state, int stock) {
        if (state == null) {
            return;
        }
        int value = clampStock(stock);
        // 原式 Math.min(value, state.max < 0 ? value : Math.max(value, 0)) 两个分支都等于 value，
        // 想「夹到补货基准」的意图从未生效；这里显式分开「-1 = 不限」与「夹到 max」。
        state.stock = value < 0 ? -1 : (state.max < 0 ? value : Math.min(value, state.max));
        setDirty();
    }

    private static int clampStock(int value) {
        if (value < -1) {
            return -1;
        }
        return Math.min(value, 999999);
    }

    /** OP 改买价/卖价；传负数 = 取消覆盖（回到配置值）。 */
    public void setBuyOverride(EntryState state, long price) {
        if (state == null) {
            return;
        }
        state.buyOverride = price < 0L ? NO_OVERRIDE : Math.min(price, MarketCatalog.PRICE_CAP);
        setDirty();
    }

    public void setSellOverride(EntryState state, long price) {
        if (state == null) {
            return;
        }
        state.sellOverride = price < 0L ? NO_OVERRIDE : Math.min(price, MarketCatalog.PRICE_CAP);
        setDirty();
    }

    /** 清掉全部价格覆盖（重载目录 = 回到配置价）。 */
    public int clearPriceOverrides() {
        int cleared = 0;
        for (EntryState state : entries.values()) {
            if (state.buyOverride != NO_OVERRIDE || state.sellOverride != NO_OVERRIDE) {
                state.buyOverride = NO_OVERRIDE;
                state.sellOverride = NO_OVERRIDE;
                cleared++;
            }
        }
        if (cleared > 0) {
            setDirty();
        }
        return cleared;
    }

    public void setDisabled(EntryState state, boolean disabled) {
        if (state == null || state.disabled == disabled) {
            return;
        }
        state.disabled = disabled;
        setDirty();
    }

    /** 立即补满某条目（回到补货基准）。 */
    public void restock(EntryState state, long now) {
        if (state == null) {
            return;
        }
        state.stock = state.max;
        state.restockAt = now;
        setDirty();
    }

    // ---------------------------------------------------------------- 玩家每日已购（件）

    /** 今日已购件数（跨日自动算 0）。 */
    public int boughtToday(UUID player, long day) {
        DayBought record = player == null ? null : players.get(player);
        if (record == null || record.day != day) {
            return 0;
        }
        return Math.max(0, record.items);
    }

    /** 累加今日已购件数。 */
    public void addBought(UUID player, long day, int items) {
        if (player == null || items <= 0) {
            return;
        }
        DayBought record = players.get(player);
        if (record == null || record.day != day) {
            players.put(player, new DayBought(day, items));
        } else {
            record.items = (int) Math.min(Integer.MAX_VALUE, (long) record.items + items);
        }
        setDirty();
    }

    /** 回滚今日已购件数。 */
    public void subtractBought(UUID player, long day, int items) {
        DayBought record = player == null ? null : players.get(player);
        if (record == null || record.day != day || items <= 0) {
            return;
        }
        record.items = Math.max(0, record.items - items);
        setDirty();
    }

    // ---------------------------------------------------------------- 清理

    /** 删除已不在目录里的条目状态（配置 reload 后调用）。 */
    public int purgeStaleState(Collection<String> validIds) {
        Set<String> keep = validIds == null ? new HashSet<>() : new HashSet<>(validIds);
        List<String> stale = new ArrayList<>();
        for (String id : entries.keySet()) {
            if (!keep.contains(id)) {
                stale.add(id);
            }
        }
        for (String id : stale) {
            entries.remove(id);
        }
        if (!stale.isEmpty()) {
            setDirty();
            PlayerSuiteMod.LOGGER.info("[market] 清理失效条目状态 {} 个", stale.size());
        }
        return stale.size();
    }

    // ---------------------------------------------------------------- 落盘

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (EntryState state : all()) {
            CompoundTag one = new CompoundTag();
            one.putString(TAG_ID, state.id());
            one.putInt(TAG_STOCK, state.stock);
            one.putInt(TAG_MAX, state.max);
            one.putLong(TAG_RESTOCK_AT, state.restockAt);
            one.putLong(TAG_BUY_OVER, state.buyOverride);
            one.putLong(TAG_SELL_OVER, state.sellOverride);
            one.putBoolean(TAG_DISABLED, state.disabled);
            list.add(one);
        }
        tag.put(TAG_ENTRIES, list);

        ListTag people = new ListTag();
        for (Map.Entry<UUID, DayBought> e : players.entrySet()) {
            CompoundTag one = new CompoundTag();
            one.putUUID(TAG_UUID, e.getKey());
            one.putLong(TAG_DAY, e.getValue().day);
            one.putInt(TAG_BOUGHT, e.getValue().items);
            people.add(one);
        }
        tag.put(TAG_PLAYERS, people);
        return tag;
    }

    private static MarketState load(CompoundTag tag, HolderLookup.Provider registries) {
        MarketState state = new MarketState();
        if (tag.contains(TAG_ENTRIES, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag one = list.getCompound(i);
                String id = one.getString(TAG_ID);
                if (id.isEmpty()) {
                    continue;
                }
                EntryState entry = new EntryState(id,
                        one.getInt(TAG_STOCK), one.getInt(TAG_MAX), one.getLong(TAG_RESTOCK_AT));
                entry.buyOverride = one.contains(TAG_BUY_OVER, Tag.TAG_LONG) ? one.getLong(TAG_BUY_OVER) : NO_OVERRIDE;
                entry.sellOverride = one.contains(TAG_SELL_OVER, Tag.TAG_LONG) ? one.getLong(TAG_SELL_OVER) : NO_OVERRIDE;
                entry.disabled = one.getBoolean(TAG_DISABLED);
                state.entries.put(id, entry);
            }
        }
        if (tag.contains(TAG_PLAYERS, Tag.TAG_LIST)) {
            ListTag people = tag.getList(TAG_PLAYERS, Tag.TAG_COMPOUND);
            for (int i = 0; i < people.size(); i++) {
                CompoundTag one = people.getCompound(i);
                if (!one.hasUUID(TAG_UUID)) {
                    continue;
                }
                state.players.put(one.getUUID(TAG_UUID),
                        new DayBought(one.getLong(TAG_DAY), one.getInt(TAG_BOUGHT)));
            }
        }
        return state;
    }
}
