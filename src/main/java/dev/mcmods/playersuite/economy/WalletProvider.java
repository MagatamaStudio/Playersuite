package dev.mcmods.playersuite.economy;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.config.SuiteConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.List;

/**
 * 内置钱包实现：数据在玩家数据附件 {@code playersuite:wallet} 上。
 *
 * <p>所有加减钱都会写进流水（可由配置关闭），并统一受 maxBalance 约束。
 */
public final class WalletProvider implements CurrencyProvider {
    public static final String ID = "playersuite:wallet";
    /** 默认（内置）提供方实例。 */
    public static final WalletProvider INSTANCE = new WalletProvider();
    private static final String TXN_DEFAULT = "general";

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, PlayerSuiteMod.MODID);

    /** 玩家钱包（余额 + 流水）。只在服务端使用，无需同步到客户端。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<WalletData>> WALLET =
            ATTACHMENT_TYPES.register("wallet", () -> AttachmentType
                    .builder(() -> new WalletData())
                    .serialize(WalletData.SERIALIZER)
                    .copyOnDeath()
                    .build());

    private WalletProvider() {
    }

    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }

    /** 取（或惰性创建）玩家的钱包。 */
    public static WalletData of(ServerPlayer player) {
        return player.getData(WALLET);
    }

    /** 标记钱包已变化（附件是可变对象时需要显式通知，保证落盘）。 */
    public static void touch(ServerPlayer player) {
        player.setData(WALLET, of(player));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public long balance(ServerPlayer player) {
        return Math.max(0L, of(player).balance());
    }

    @Override
    public boolean withdraw(ServerPlayer player, long amount) {
        return withdraw(player, amount, TXN_DEFAULT);
    }

    @Override
    public void deposit(ServerPlayer player, long amount) {
        deposit(player, amount, TXN_DEFAULT);
    }

    @Override
    public boolean setBalance(ServerPlayer player, long amount) {
        WalletData wallet = of(player);
        long before = wallet.balance();
        wallet.set(amount);
        wallet.record(wallet.balance() - before, Economy.TXN_PREFIX + "admin_set");
        touch(player);
        return true;
    }

    @Override
    public boolean supportsOfflinePayout() {
        return true;
    }

    @Override
    public Component format(long amount) {
        return Component.literal(Economy.formatString(amount)).withStyle(ChatFormatting.GOLD);
    }

    /** 加钱并记录原因（原因会以 txn.playersuite.xxx 翻译键写入流水）。 */
    public static void deposit(ServerPlayer player, long amount, String reasonKey) {
        if (amount <= 0L) {
            return;
        }
        WalletData wallet = of(player);
        long applied = wallet.add(amount);
        if (applied > 0L) {
            wallet.record(applied, Economy.reason(reasonKey));
            touch(player);
        }
    }

    /** 扣钱并记录原因；余额不足返回 false 且不变动。 */
    public static boolean withdraw(ServerPlayer player, long amount, String reasonKey) {
        if (amount <= 0L) {
            return true;
        }
        WalletData wallet = of(player);
        if (!wallet.take(amount)) {
            return false;
        }
        wallet.record(-amount, Economy.reason(reasonKey));
        touch(player);
        return true;
    }

    /** 最近流水。 */
    public static List<WalletData.Txn> transactions(ServerPlayer player) {
        return of(player).log();
    }

    /**
     * 初始赠送：每个玩家（UUID）只发放一次。
     *
     * <p>不能用「钱包附件是否存在」判断首次进服：{@link WalletData#isBlank()} 为真时序列化器返回 null，
     * 玩家把余额花光后重登会被当成首次进服，从而可以无限刷初始资金。
     */
    public static void grantInitial(ServerPlayer player) {
        long initial = SuiteConfig.initialBalance();
        if (initial <= 0L) {
            return;
        }
        InitialGrants grants = InitialGrants.of(player.server);
        java.util.UUID id = player.getUUID();
        boolean hadWallet = player.getExistingData(WALLET).isPresent();
        if (hadWallet) {
            // 升级前就已经有钱包数据的玩家：只补标记，不补发
            grants.markGranted(id);
            return;
        }
        if (!grants.markGranted(id)) {
            return;                       // 已经发过（例如花光余额后重登）
        }
        WalletData wallet = new WalletData();
        wallet.add(initial);
        wallet.record(initial, Economy.TXN_PREFIX + "initial");
        player.setData(WALLET, wallet);
    }
}
