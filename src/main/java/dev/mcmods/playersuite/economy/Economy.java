package dev.mcmods.playersuite.economy;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.config.SuiteConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;

import java.util.List;
import java.util.UUID;

/**
 * 全模组统一的金钱入口。
 *
 * <p>所有功能（仓库扩容、银行、玩家商店、官方市场、称号、签到）都只调用这里，
 * 不直接碰钱包实现，因此：
 * <ul>
 *     <li>换货币只需要改配置或调用 {@link #bind(CurrencyProvider)} 对接其它经济 mod；</li>
 *     <li>收支流水、上限、离线待领取等规则集中在一处，不会出现某个功能绕过校验。</li>
 * </ul>
 *
 * <p>所有方法都只在逻辑服务端调用。
 */
public final class Economy {
    /** 流水原因的翻译键前缀，例如 {@code txn.playersuite.bank_interest}。 */
    public static final String TXN_PREFIX = "txn.playersuite.";

    private static CurrencyProvider bound;

    private Economy() {
    }

    /** 由 mod 入口调用，注册钱包数据附件。 */
    public static void register(IEventBus modEventBus) {
        WalletProvider.register(modEventBus);
    }

    /**
     * 对接外部经济系统：在 mod 构造阶段调用本方法传入自己的实现即可，
     * 之后全部功能都会使用该实现。传入 null 恢复内置钱包。
     */
    public static void bind(CurrencyProvider provider) {
        bound = provider;
        PlayerSuiteMod.LOGGER.info("经济系统提供方已切换为 {}", provider == null ? "内置钱包" : provider.id());
    }

    public static CurrencyProvider provider() {
        return bound == null ? WalletProvider.INSTANCE : bound;
    }

    /** 是否为内置钱包（只有内置钱包支持流水与离线待领取）。 */
    public static boolean builtinWallet() {
        return provider() == WalletProvider.INSTANCE;
    }

    // ------------------------------------------------------------------ 余额

    public static long balance(ServerPlayer player) {
        if (player == null) {
            return 0L;
        }
        return Math.max(0L, provider().balance(player));
    }

    public static boolean canAfford(ServerPlayer player, long amount) {
        return amount <= 0L || balance(player) >= amount;
    }

    /** 扣款；失败（余额不足）返回 false。 */
    public static boolean withdraw(ServerPlayer player, long amount, String reasonKey) {
        if (amount <= 0L) {
            return true;
        }
        if (builtinWallet()) {
            return WalletProvider.withdraw(player, amount, reasonKey);
        }
        return provider().withdraw(player, amount);
    }

    /** 加钱。 */
    public static void deposit(ServerPlayer player, long amount, String reasonKey) {
        if (amount <= 0L) {
            return;
        }
        if (builtinWallet()) {
            WalletProvider.deposit(player, amount, reasonKey);
            return;
        }
        provider().deposit(player, amount);
    }

    /** 管理员直接设置余额。 */
    public static boolean setBalance(ServerPlayer player, long amount) {
        return provider().setBalance(player, Math.max(0L, amount));
    }

    /** 从 from 转给 to；余额不足返回 false，两边都不动。 */
    public static boolean transfer(ServerPlayer from, ServerPlayer to, long amount, String reasonKey) {
        if (amount <= 0L) {
            return true;
        }
        if (!withdraw(from, amount, reasonKey)) {
            return false;
        }
        deposit(to, amount, reasonKey);
        return true;
    }

    // ------------------------------------------------------------- 离线待领取

    /** 给离线玩家记一笔待领取收入（仅内置钱包支持；外部经济请自行处理或改判在线）。 */
    public static void payOffline(MinecraftServer server, UUID owner, long amount, String reasonKey) {
        if (amount <= 0L || owner == null) {
            return;
        }
        PendingPayouts.of(server).add(owner, amount, reason(reasonKey));
    }

    public static long pendingTotal(ServerPlayer player) {
        return builtinWallet() ? PendingPayouts.of(player.server).total(player.getUUID()) : 0L;
    }

    public static int pendingCount(ServerPlayer player) {
        return builtinWallet() ? PendingPayouts.of(player.server).count(player.getUUID()) : 0;
    }

    /** 领取待领取收入（受钱包余额上限约束，未领完的部分保留），返回实际领到的数额。 */
    public static long claimPending(ServerPlayer player) {
        if (!builtinWallet()) {
            return 0L;
        }
        // 先算「还能装下多少」再领：原来先 claimAll 全删、再按 maxBalance 截断入账，
        // 余额接近上限时差额会被静默销毁（付款方已付出、收款方没到账）。
        long room = Math.max(0L, SuiteConfig.maxBalance() - balance(player));
        if (room <= 0L) {
            return 0L;
        }
        long amount = PendingPayouts.of(player.server).claimUpTo(player.getUUID(), room);
        if (amount > 0L) {
            deposit(player, amount, "pending");
        }
        return amount;
    }

    // ------------------------------------------------------------------ 展示

    /** 千分位 + 货币符号，例如 {@code 金 12,300}。 */
    public static String formatString(long amount) {
        return SuiteConfig.currencySymbol() + " " + group(amount);
    }

    /** 只带千分位的数字（不含符号）。 */
    public static String group(long amount) {
        return String.format(java.util.Locale.ROOT, "%,d", amount);
    }

    /** 带颜色的金额组件。 */
    public static Component label(long amount) {
        return Component.literal(formatString(amount)).withStyle(builtinColor(amount));
    }

    private static ChatFormatting builtinColor(long amount) {
        return amount < 0L ? ChatFormatting.RED : ChatFormatting.GOLD;
    }

    /**
     * 界面用的整数余额：{@code ContainerData} 只能同步 int，
     * 超过 21 亿时按上限显示（实际结算仍然使用完整 long，不会因为显示而少扣钱）。
     */
    public static int intForGui(long value) {
        if (value <= 0L) {
            return 0;
        }
        return (int) Math.min(value, (long) Integer.MAX_VALUE - 1L);
    }

    /** 最近收支流水。 */
    public static List<WalletData.Txn> transactions(ServerPlayer player) {
        return builtinWallet() ? WalletProvider.transactions(player) : List.of();
    }

    /** 首次进服发放启动资金。 */
    public static void grantInitial(ServerPlayer player) {
        WalletProvider.grantInitial(player);
    }

    /** 把内部记录用的原因字符串还原成翻译键（去掉前缀）。 */
    public static String reasonKey(String stored) {
        if (stored == null || stored.isEmpty()) {
            return TXN_PREFIX + "general";
        }
        return stored.startsWith(TXN_PREFIX) ? stored : TXN_PREFIX + stored;
    }

    /** 把原因字符串规范化为带前缀的翻译键。 */
    public static String reason(String key) {
        if (key == null || key.isEmpty()) {
            return TXN_PREFIX + "general";
        }
        return key.startsWith(TXN_PREFIX) ? key : TXN_PREFIX + key;
    }
}
