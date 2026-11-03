package dev.mcmods.playersuite.ui;

import dev.mcmods.playersuite.bank.BankService;
import dev.mcmods.playersuite.checkin.CheckinService;
import dev.mcmods.playersuite.config.FeatureKeys;
import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.hub.HubMenu;
import dev.mcmods.playersuite.mail.MailService;
import dev.mcmods.playersuite.market.MarketService;
import dev.mcmods.playersuite.shop.ShopService;
import dev.mcmods.playersuite.title.TitleService;
import dev.mcmods.playersuite.warehouse.WarehouseService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 功能页面的统一打开入口：总入口界面的按钮、{@code /ps <功能>}、进服提示的点击链接
 * 全部走这里，因此「哪个功能被禁用」「哪个功能还没接线」只需要在这一处判断。
 */
public final class FeatureOpeners {
    public static final String HUB = "hub";
    public static final String WAREHOUSE = FeatureKeys.WAREHOUSE;
    public static final String MAIL = FeatureKeys.MAIL;
    public static final String BANK = FeatureKeys.BANK;
    public static final String SHOP = FeatureKeys.SHOP;
    public static final String MARKET = FeatureKeys.MARKET;
    public static final String TITLE = FeatureKeys.TITLE;
    public static final String CHECKIN = FeatureKeys.CHECKIN;

    /** 总入口界面上的功能顺序（按钮编号按此顺序 = BTN_CUSTOM + 下标）。 */
    public static final List<String> FEATURES = List.of(FeatureKeys.ALL);

    private FeatureOpeners() {
    }

    public static boolean isKnown(String key) {
        return key != null && (HUB.equals(key) || FEATURES.contains(key));
    }

    public static boolean enabled(String key) {
        return HUB.equals(key) || SuiteConfig.featureEnabled(key);
    }

    /** 按 {@link #FEATURES} 顺序生成的启用位掩码（发给客户端，按钮置灰用）。 */
    public static int enabledMask() {
        int mask = 0;
        for (int i = 0; i < FEATURES.size(); i++) {
            if (enabled(FEATURES.get(i))) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    /** 位掩码里第 index 位是否启用。 */
    public static boolean enabledAt(int mask, int index) {
        return index >= 0 && index < FEATURES.size() && (mask & (1 << index)) != 0;
    }

    /** 第 index 个功能的键名，越界返回 null。 */
    public static String keyAt(int index) {
        return index >= 0 && index < FEATURES.size() ? FEATURES.get(index) : null;
    }

    /**
     * 打开某个功能的页面。
     *
     * @param page 起始页码（各功能内部自行夹到有效范围）
     */
    public static void open(ServerPlayer player, String key, int page) {
        if (player == null || key == null) {
            return;
        }
        if (!isKnown(key)) {
            player.displayClientMessage(Component.translatable("playersuite.ui.unknownFeature", key), false);
            return;
        }
        if (!enabled(key)) {
            player.displayClientMessage(Component.translatable("playersuite.ui.featureDisabled", key), false);
            return;
        }
        switch (key) {
            case HUB -> HubMenu.open(player);
            case WAREHOUSE -> WarehouseService.open(player, player, page);
            case MAIL -> MailService.open(player, player, page);
            case BANK -> BankService.open(player, player, page);
            case SHOP -> ShopService.open(player, player, page);
            case MARKET -> MarketService.open(player, player, page);
            case TITLE -> TitleService.open(player, player, page);
            case CHECKIN -> CheckinService.open(player, player, page);
            default -> player.displayClientMessage(Component.translatable("playersuite.ui.unknownFeature", key), false);
        }
    }

    /**
     * 以 {@code viewer} 身份打开 {@code target} 的功能页面（OP 代开他人页面）。
     *
     * <p>{@code /ps <功能> <玩家>} 原来调的是 {@code open(viewer, key, page)}，target 被直接丢弃：
     * 命令返回成功，实际打开的却是查看者自己的页面。这里把 target 真正透传给各功能已有的
     * {@code open(viewer, target, page)} 重载。
     */
    public static void open(ServerPlayer viewer, ServerPlayer target, String key, int page) {
        if (viewer == null || key == null) {
            return;
        }
        if (!isKnown(key)) {
            viewer.displayClientMessage(Component.translatable("playersuite.ui.unknownFeature", key), false);
            return;
        }
        if (!enabled(key)) {
            viewer.displayClientMessage(Component.translatable("playersuite.ui.featureDisabled", key), false);
            return;
        }
        ServerPlayer effective = target == null || target.isRemoved() ? viewer : target;
        switch (key) {
            case HUB -> HubMenu.open(viewer);
            case WAREHOUSE -> WarehouseService.open(viewer, effective, page);
            case MAIL -> MailService.open(viewer, effective, page);
            case BANK -> BankService.open(viewer, effective, page);
            case SHOP -> ShopService.open(viewer, effective, page);
            case MARKET -> MarketService.open(viewer, effective, page);
            case TITLE -> TitleService.open(viewer, effective, page);
            case CHECKIN -> CheckinService.open(viewer, effective, page);
            default -> viewer.displayClientMessage(Component.translatable("playersuite.ui.unknownFeature", key), false);
        }
    }

    /** 可点击的「打开功能总入口」文本，用于进服提示与说明。 */
    public static Component hubLink() {
        // withStyle 链必须从 translatable 直接发起（返回 MutableComponent）
        return Component.translatable("playersuite.hub.link")
                .withStyle(ChatFormatting.AQUA)
                .withStyle(style -> style.withClickEvent(new ClickEvent(
                        ClickEvent.Action.RUN_COMMAND, "/" + SuiteConfig.hubRoot())));
    }
}
