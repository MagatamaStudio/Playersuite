package dev.mcmods.playersuite.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.ui.FeatureOpeners;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 统一入口指令 {@code /ps}（主名与别名可在配置里改）：
 *
 * <pre>
 * /ps                          打开总入口界面（七个功能都在里面）
 * /ps hub                      同上
 * /ps &lt;功能&gt;                   直接打开某个功能页（warehouse|mail|bank|shop|market|title|checkin）
 * /ps money query [玩家]       查询余额（查他人需 OP 2）
 * /ps money add|take|set &lt;金额&gt; [玩家]  调整余额（需 OP 2，代改他人需 OP 2）
 * /ps help                     显示说明
 * </pre>
 *
 * <p>另外当 {@code registerShortcutCommands = true} 时，会额外注册
 * {@code /mail /bank /shop /market /title /checkin} 六条快捷指令，等价于 {@code /ps <功能>}。
 *
 * <p>注意：指令名必须是 ASCII（Brigadier 不支持中文指令名）。
 */
public final class SuiteCommand {
    /** 金钱调整类型。 */
    private enum MoneyAction {
        ADD, TAKE, SET
    }

    private SuiteCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String name : commandNames()) {
            dispatcher.register(buildRoot(name));
        }
        if (SuiteConfig.registerShortcutCommands()) {
            for (String feature : FeatureOpeners.FEATURES) {
                if (FeatureOpeners.WAREHOUSE.equals(feature)) {
                    continue; // 仓库已有 /warehouse 指令族
                }
                registerShortcut(dispatcher, feature);
            }
        }
    }

    /** 本次注册的主指令名与别名（去重）。 */
    public static List<String> commandNames() {
        List<String> names = new ArrayList<>();
        names.add(SuiteConfig.hubRoot());
        names.addAll(SuiteConfig.hubAliases());
        return names.stream().distinct().toList();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRoot(String name) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name)
                .executes(ctx -> openHub(ctx, senderOrThrow(ctx)))
                .then(Commands.literal("hub")
                        .executes(ctx -> openHub(ctx, senderOrThrow(ctx))))
                .then(Commands.literal("help")
                        .executes(ctx -> help(ctx)))
                .then(Commands.literal("money")
                        .then(Commands.literal("query")
                                .executes(ctx -> queryMoney(ctx, senderOrThrow(ctx)))
                                .then(playerArgument().executes(ctx -> queryMoney(ctx, EntityArgument.getPlayer(ctx, "player")))))
                        .then(moneyCommand(MoneyAction.ADD))
                        .then(moneyCommand(MoneyAction.TAKE))
                        .then(moneyCommand(MoneyAction.SET)));

        // /ps <功能>：直接打开对应页面
        for (String feature : FeatureOpeners.FEATURES) {
            root.then(Commands.literal(feature)
                    .executes(ctx -> openFeature(ctx, senderOrThrow(ctx), feature))
                    .then(playerArgument().executes(ctx -> openFeatureAs(ctx, feature))));
        }
        return root;
    }

    private static void registerShortcut(CommandDispatcher<CommandSourceStack> dispatcher, String feature) {
        dispatcher.register(Commands.literal(feature)
                .executes(ctx -> openFeature(ctx, senderOrThrow(ctx), feature))
                .then(Commands.literal("open")
                        .executes(ctx -> openFeature(ctx, senderOrThrow(ctx), feature)))
                .then(playerArgument().executes(ctx -> openFeatureAs(ctx, feature))));
    }

    /** {@code money add|take|set <金额> [玩家]}（需 OP 2）。 */
    private static LiteralArgumentBuilder<CommandSourceStack> moneyCommand(MoneyAction action) {
        return Commands.literal(action.name().toLowerCase(Locale.ROOT))
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("amount", LongArgumentType.longArg(0L, Long.MAX_VALUE / 4L))
                        .executes(ctx -> changeMoney(ctx, action, senderOrThrow(ctx)))
                        .then(playerArgument().executes(ctx -> changeMoney(ctx, action, EntityArgument.getPlayer(ctx, "player")))));
    }

    /** {@code [玩家]} 可选参数：只有 OP 2 可以指定他人。 */
    private static RequiredArgumentBuilder<CommandSourceStack, ?> playerArgument() {
        return Commands.argument("player", EntityArgument.players())
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .suggests((source, builder) -> SharedSuggestionProvider.suggest(
                        source.getSource().getServer().getPlayerList().getPlayers().stream()
                                .map(player -> player.getGameProfile().getName())
                                .toList(),
                        builder));
    }

    // ------------------------------------------------------------ 执行

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSystemMessage(Component.translatable("playersuite.command.help.hub"));
        for (String feature : FeatureOpeners.FEATURES) {
            source.sendSystemMessage(Component.translatable("playersuite.command.help.feature",
                    SuiteConfig.hubRoot(), feature, Component.translatable("playersuite.hub.feature." + feature)));
        }
        source.sendSystemMessage(Component.translatable("playersuite.command.help.money"));
        return 1;
    }

    private static int openHub(CommandContext<CommandSourceStack> ctx, ServerPlayer player) throws CommandSyntaxException {
        FeatureOpeners.open(player, FeatureOpeners.HUB, 0);
        return 1;
    }

    private static int openFeature(CommandContext<CommandSourceStack> ctx, ServerPlayer player, String feature) {
        FeatureOpeners.open(player, feature, 0);
        return 1;
    }

    /** {@code /ps <功能> <玩家>}：OP 代开他人页面（自己始终是对话框的查看者）。 */
    private static int openFeatureAs(CommandContext<CommandSourceStack> ctx, String feature)
            throws CommandSyntaxException {
        ServerPlayer viewer = senderOrThrow(ctx);
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        if (!SuiteConfig.allowManageOthers() && target != viewer) {
            viewer.displayClientMessage(Component.translatable("playersuite.error.manageOthersDisabled"), false);
            return 0;
        }
        // target 必须真正透传下去，否则命令返回成功却只打开查看者自己的页面
        FeatureOpeners.open(viewer, target, feature, 0);
        return 1;
    }

    private static int queryMoney(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        ctx.getSource().sendSuccess(() -> Component.translatable("playersuite.money.query",
                player.getGameProfile().getName(), Economy.label(Economy.balance(player))), false);
        return 1;
    }

    private static int changeMoney(CommandContext<CommandSourceStack> ctx, MoneyAction action, ServerPlayer player)
            throws CommandSyntaxException {
        long amount = LongArgumentType.getLong(ctx, "amount");
        long before = Economy.balance(player);
        boolean ok;
        switch (action) {
            case ADD -> {
                Economy.deposit(player, amount, "command");
                ok = true;
            }
            case TAKE -> ok = Economy.withdraw(player, amount, "command");
            default -> ok = Economy.setBalance(player, amount);
        }
        if (!ok) {
            ctx.getSource().sendFailure(Component.translatable("playersuite.money.fail",
                    action.name().toLowerCase(Locale.ROOT), player.getGameProfile().getName(),
                    Economy.label(amount), Economy.label(before)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("playersuite.money.ok",
                action.name().toLowerCase(Locale.ROOT), player.getGameProfile().getName(),
                Economy.label(amount), Economy.label(Economy.balance(player))), true);
        return 1;
    }

    private static ServerPlayer senderOrThrow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getSource().getPlayerOrException();
    }
}
