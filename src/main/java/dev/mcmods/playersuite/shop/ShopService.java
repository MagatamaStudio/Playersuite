package dev.mcmods.playersuite.shop;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.bank.BankService;
import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.ui.FeatureOpeners;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import dev.mcmods.playersuite.ui.SuiteMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家商店的业务中枢：打开页面、上架托管、补库存、购买结算、税费、
 * OP 强制下架/退回/清店、文本输入校验与登录同步。
 *
 * <p><b>托管模型</b>：上架即把真实物品移入全局 SavedData（{@link ShopStorage}），
 * 购买从托管仓发货（与卖家是否在线无关），下架时退回。玩家货架槽位背后
 * 直接就是这个托管仓的视图，因此「放物品 / 取物品」天然对应「上架 / 退物」。
 *
 * <p>所有方法都只在逻辑服务端调用。
 */
public final class ShopService {
    /** 功能键（同 {@code FeatureOpeners.SHOP}）。 */
    public static final String FEATURE = FeatureOpeners.SHOP;
    /** 逛店页数量档（按钮档位 = 下标）。 */
    public static final int[] BUY_TIERS = {1, 16, 64};
    /** 文本输入动作键。 */
    public static final String ACTION_NAME = "name";
    public static final String ACTION_PRICE_PREFIX = "price_";

    /** 防连点：同一托管键同一 tick 只允许一次成交尝试。 */
    private static final Map<String, Integer> LAST_DEAL_TICK = new ConcurrentHashMap<>();

    private ShopService() {
    }

    // ------------------------------------------------------------------ 数据访问

    public static ShopData data(Player player) {
        return player.getData(ShopAttachments.SHOP_DATA);
    }

    /** 可变对象改完后重新 setData 触发脏标记（否则不存档）。 */
    public static void touch(ServerPlayer owner, ShopData data) {
        owner.setData(ShopAttachments.SHOP_DATA, data);
    }

    public static ShopStorage storage(MinecraftServer server) {
        return ShopStorage.of(server);
    }

    public static ShopCatalog catalog(MinecraftServer server) {
        return ShopCatalog.of(server);
    }

    /** 店铺展示名：没设置过时显示「XX 的店」。 */
    public static String shopNameOr(ServerPlayer owner, ShopData data) {
        String name = data.shopName();
        if (!name.isEmpty()) {
            return name;
        }
        return owner.getGameProfile().getName();
    }

    public static Component itemLabel(ItemStack stack) {
        return stack.isEmpty() ? Component.literal("-") : stack.getHoverName();
    }

    // ------------------------------------------------------------------ 打开页面

    /** 当前查看者是否有权查看/管理 target 的商店（管理他人还受 allowManageOthers 约束）。 */
    public static boolean canView(ServerPlayer viewer, ServerPlayer target) {
        return isSame(viewer, target)
                || (SuiteConfig.allowManageOthers() && viewer.hasPermissions(SuiteConfig.managePermission()));
    }

    public static boolean isSame(Player a, Player b) {
        return a != null && b != null && a.getUUID().equals(b.getUUID());
    }

    /**
     * 打开 {@code target} 的商店（「我的商店」页；OP 管理页同款布局）。
     *
     * @param page 起始页码（自动夹取）
     */
    public static void open(ServerPlayer viewer, ServerPlayer target, int page) {
        boolean self = isSame(viewer, target);
        if (!self && !canView(viewer, target)) {
            say(viewer, "playersuite.shop.msg.noPermission", SuiteConfig.managePermission());
            target = viewer;
            self = true;
        }
        MinecraftServer server = viewer.server;
        ShopData data = data(target);
        int listings = SuiteConfig.shopListings();
        int perPage = SuiteConfig.shopBrowsePageSize();
        int pages = Math.max(1, (listings + perPage - 1) / perPage);
        int clamped = Mth.clamp(page, 0, pages - 1);
        int rows = Math.max(1, Math.min(perPage, listings - clamped * perPage));
        int[] rowSlots = new int[rows];
        long[] rowPrice = new long[rows];
        int[] rowStock = new int[rows];
        for (int i = 0; i < rows; i++) {
            int slot = clamped * perPage + i;
            rowSlots[i] = slot;
            ShopData.Listing l = slot < listings ? data.getListing(slot) : null;
            if (l != null) {
                rowPrice[i] = l.price;
                rowStock[i] = l.stock;
            }
        }
        final boolean fSelf = self;
        final boolean fManage = fSelf || viewer.hasPermissions(SuiteConfig.managePermission());
        final boolean fAdmin = !fSelf && viewer.hasPermissions(SuiteConfig.adminPermission());
        String ownerName = target.getGameProfile().getName();
        String shopName = shopNameOr(target, data);
        long income = data.todayIncome();
        int listingCount = data.listingCount();
        int listedUnits = data.listedUnits();
        long balance = Economy.balance(viewer);
        Component title = Component.translatable(self ? "playersuite.shop.title" : "playersuite.shop.title.view",
                shopName);
        final ServerPlayer owner = target;
        final int fPage = clamped;
        viewer.openMenu(new SuiteMenuProvider(
                        (containerId, inventory, sender) -> new ShopMenu(containerId, inventory,
                                viewer, owner, fPage, pages, fManage, fAdmin, fSelf, rowSlots),
                        title),
                buf -> ShopMenu.writeTo(buf, balance, fPage, pages, fManage, rows, perPage,
                        fSelf, fAdmin, ownerName, shopName, income, listingCount, listedUnits,
                        rowPrice, rowStock));
    }

    /** 打开全服逛店页。 */
    public static void openBrowse(ServerPlayer viewer, int page) {
        MinecraftServer server = viewer.server;
        List<ShopCatalog.Entry> all = catalog(server).list();
        int pageSize = SuiteConfig.shopBrowsePageSize();
        int pages = Math.max(1, (all.size() + pageSize - 1) / pageSize);
        int clamped = Mth.clamp(page, 0, pages - 1);
        int from = clamped * pageSize;
        int rows = Math.max(0, Math.min(pageSize, all.size() - from));
        long balance = Economy.balance(viewer);
        boolean manage = false;
        viewer.openMenu(new SuiteMenuProvider(
                        (containerId, inventory, sender) -> new ShopBrowseMenu(containerId, inventory,
                                viewer, all, from, rows, clamped, pages, pageSize),
                        Component.translatable("playersuite.shop.browse.title")),
                buf -> ShopBrowseMenu.writeTo(buf, balance, clamped, pages, manage, rows,
                        all, from, pageSize));
    }

    /** 点击后刷新当前页（不改变查看目标）。 */
    public static void refresh(ServerPlayer viewer, int page) {
        if (viewer.containerMenu instanceof ShopMenu menu) {
            ServerPlayer owner = viewer.server.getPlayerList().getPlayer(menu.ownerId());
            if (owner != null) {
                open(viewer, owner, page);
            } else {
                say(viewer, "playersuite.shop.msg.ownerOffline");
                openBrowse(viewer, 0);
            }
        } else if (viewer.containerMenu instanceof ShopBrowseMenu) {
            openBrowse(viewer, page);
        }
    }

    // ------------------------------------------------------------------ 托管对账

    /**
     * 货架槽位与托管仓/目录对账（一切放取路径的统一收口）。
     *
     * <p>约定：托管仓数量是库存的唯一事实；ShopData 的单价是本店的唯一事实；
     * 目录是两者的镜像，任何一端缺了就补、多了就删。
     *
     * @param notify 是否在「新上架」时给店主提示（tick/对账路径传 false 防刷屏）
     * @return 是否发生了实际变更（用于登录统计）
     */
    public static boolean reconcileSlot(ServerPlayer owner, int slot, boolean notify) {
        if (owner == null || owner.isRemoved()) {
            return false;
        }
        MinecraftServer server = owner.server;
        ShopStorage st = storage(server);
        ShopCatalog cat = catalog(server);
        String key = ShopStorage.key(owner.getUUID(), slot);
        ShopData data = data(owner);
        ShopData.Listing listing = data.getListing(slot);
        int count = st.count(key);

        if (count <= 0) {
            boolean had = !st.remove(key).isEmpty();
            boolean changed = listing != null;
            if (changed) {
                data.removeListing(slot);
                touch(owner, data);
            }
            changed |= cat.remove(owner.getUUID(), slot);
            changed |= had;
            // 注意：绝不在这里重建菜单——本函数正在原版 clicked() 的处理中途运行，
            // 换菜单会把光标物品弄丢（结构变化的行按钮由客户端按槽位状态自动刷新）。
            return changed;
        }

        ItemStack live = st.copy(key);
        boolean dirty = false;
        if (listing == null) {
            listing = new ShopData.Listing(live.copyWithCount(1),
                    Math.max(1L, SuiteConfig.shopMinPrice()), count, System.currentTimeMillis());
            data.putListing(slot, listing);
            dirty = true;
            if (notify) {
                say(owner, "playersuite.shop.msg.listed", itemLabel(live), count,
                        Economy.label(listing.price));
            }
        } else {
            if (!ItemStack.isSameItemSameComponents(listing.sample, live)) {
                listing.sample = live.copyWithCount(1);
                dirty = true;
            }
            if (listing.stock != count) {
                listing.stock = count;
                dirty = true;
            }
        }
        if (dirty) {
            touch(owner, data);
        }
        ShopCatalog.Entry entry = cat.find(owner.getUUID(), slot);
        String ownerName = owner.getGameProfile().getName();
        String shopName = shopNameOr(owner, data);
        boolean catChanged;
        if (entry == null) {
            cat.upsert(new ShopCatalog.Entry(owner.getUUID(), ownerName, shopName, slot,
                    listing.sample, listing.price, count, listing.listedAt));
            catChanged = true;
        } else {
            catChanged = entry.stock != count || entry.price != listing.price
                    || !ownerName.equals(entry.sellerName) || !shopName.equals(entry.shopName);
            entry.stock = count;
            entry.price = listing.price;   // 价格以店主的货架设置为准
            entry.sellerName = ownerName;
            entry.shopName = shopName;
            if (catChanged) {
                cat.touch(entry);
            }
        }
        return dirty || catChanged;
    }

    // ------------------------------------------------------------------ 库存操作

    /** 从背包扣 {@code amount} 件同类商品加进货架库存。 */
    public static boolean addStock(ServerPlayer seller, int slot, int amount) {
        if (amount <= 0) {
            return false;
        }
        ShopData data = data(seller);
        ShopData.Listing listing = data.getListing(slot);
        if (listing == null || listing.sample.isEmpty()) {
            say(seller, "playersuite.shop.msg.shelfEmpty");
            return false;
        }
        ShopStorage st = storage(seller.server);
        String key = ShopStorage.key(seller.getUUID(), slot);
        int held = st.count(key);
        if (held <= 0) {
            reconcileSlot(seller, slot, false);
            say(seller, "playersuite.shop.msg.shelfEmpty");
            return false;
        }
        long capped = (long) held + (long) amount;
        int maxStock = SuiteConfig.shopMaxStock();
        if (capped > maxStock) {
            say(seller, "playersuite.shop.msg.stockMax", maxStock);
            return false;
        }
        int have = countInInventory(seller, listing.sample);
        if (have < amount) {
            say(seller, "playersuite.shop.msg.stockShortage", have);
            return false;
        }
        consumeFromInventory(seller, listing.sample, amount);
        ItemStack live = st.getLive(key);
        live.grow(amount);
        st.commit();
        listing.stock = live.getCount();
        touch(seller, data);
        ShopCatalog.Entry entry = catalog(seller.server).find(seller.getUUID(), slot);
        if (entry != null) {
            entry.stock = live.getCount();
            catalog(seller.server).touch(entry);
        }
        say(seller, "playersuite.shop.msg.stockAdded", amount, live.getCount());
        return true;
    }

    /** 自助下架：托管物品退回自己（背包满则掉在脚下）。 */
    public static boolean takeDown(ServerPlayer seller, int slot) {
        ShopStorage st = storage(seller.server);
        ShopCatalog cat = catalog(seller.server);
        String key = ShopStorage.key(seller.getUUID(), slot);
        ShopData data = data(seller);
        ShopData.Listing listing = data.getListing(slot);
        ItemStack back = st.remove(key);
        data.removeListing(slot);
        touch(seller, data);
        cat.remove(seller.getUUID(), slot);
        if (back.isEmpty()) {
            say(seller, "playersuite.shop.msg.shelfEmpty");
            return listing != null;
        }
        deliver(seller, back);
        say(seller, "playersuite.shop.msg.unlisted", itemLabel(back), back.getCount());
        return true;
    }

    // ------------------------------------------------------------------ 购买

    /**
     * 逛店页购买。
     *
     * @param globalRow 目录全局行号（服务端按当前目录重新解析）
     * @param tier      {@link #BUY_TIERS} 下标
     */
    public static boolean purchase(ServerPlayer buyer, int globalRow, int tier) {
        if (tier < 0 || tier >= BUY_TIERS.length) {
            return false;
        }
        int qty = BUY_TIERS[tier];
        MinecraftServer server = buyer.server;
        ShopStorage st = storage(server);
        ShopCatalog cat = catalog(server);
        ShopCatalog.Entry entry = cat.row(globalRow);
        if (entry == null) {
            say(buyer, "playersuite.shop.msg.noStock");
            return false;
        }
        if (entry.seller.equals(buyer.getUUID())) {
            say(buyer, "playersuite.shop.msg.selfBuy");
            return false;
        }
        String key = entry.storageKey();
        // 防连点：同一托管键同一 tick 只允许一次成交
        int tick = server.getTickCount();
        synchronized (LAST_DEAL_TICK) {
            Integer last = LAST_DEAL_TICK.get(key);
            if (last != null && last == tick) {
                say(buyer, "playersuite.shop.msg.tooFast");
                return false;
            }
            if (LAST_DEAL_TICK.size() > 4096) {
                LAST_DEAL_TICK.clear();
            }
            LAST_DEAL_TICK.put(key, tick);
        }
        ServerPlayer seller = server.getPlayerList().getPlayer(entry.seller);
        if (seller == null) {
            if (!SuiteConfig.shopOfflinePayout()) {
                say(buyer, "playersuite.shop.msg.offlineClosed");
                return false;
            }
            if (!Economy.builtinWallet()) {
                say(buyer, "playersuite.shop.msg.offlineEconomy");
                return false;
            }
        }
        ItemStack live = st.getLive(key);
        if (live.isEmpty() || live.getCount() < qty) {
            int now = st.count(key);
            if (now <= 0) {
                st.remove(key);
                cat.remove(entry.seller, entry.slot);
            } else if (entry.stock != now) {
                entry.stock = now;
                cat.touch(entry);
            }
            if (seller != null) {
                reconcileSlot(seller, entry.slot, false);
            }
            say(buyer, "playersuite.shop.msg.noStock");
            return false;
        }
        long price = Math.max(1L, entry.price);
        if (price > Long.MAX_VALUE / qty) {
            say(buyer, "playersuite.shop.msg.noStock");
            return false;
        }
        long gross = price * qty;
        ItemStack toGive = live.copyWithCount(qty);
        // 先确认背包放得下，再动钱（避免扣款后发货失败的竞态）
        InvWrapper inv = new InvWrapper(buyer.getInventory());
        if (!ItemHandlerHelper.insertItemStacked(inv, toGive.copy(), true).isEmpty()) {
            say(buyer, "playersuite.shop.msg.inventoryFull");
            return false;
        }
        if (!Economy.withdraw(buyer, gross, "shop_buy")) {
            say(buyer, "playersuite.shop.msg.noMoney", Economy.label(gross));
            return false;
        }
        // 发货前二次确认托管数量（同 tick 内理论不会再变，防御性处理）
        live = st.getLive(key);
        if (live.isEmpty() || live.getCount() < qty) {
            Economy.deposit(buyer, gross, "shop_refund");
            say(buyer, "playersuite.shop.msg.noStock");
            return false;
        }
        live.shrink(qty);
        if (live.isEmpty()) {
            st.remove(key);
        } else {
            st.commit();
        }
        ItemStack rest = ItemHandlerHelper.insertItemStacked(inv, toGive, false);
        if (!rest.isEmpty()) {
            // 兜底：把没塞进去的部分放回托管仓并退款
            ItemStack cur = st.getLive(key);
            if (cur.isEmpty()) {
                st.put(key, rest);
            } else {
                cur.grow(rest.getCount());
                st.commit();
            }
            Economy.deposit(buyer, gross, "shop_refund");
            say(buyer, "playersuite.shop.msg.inventoryFull");
            return false;
        }
        int newCount = st.count(key);
        if (newCount <= 0) {
            cat.remove(entry.seller, entry.slot);
        } else {
            entry.stock = newCount;
            cat.touch(entry);
        }
        // 税费与入账：统一用整数万分比（bps），避免 (long)(0.29 * 100.0) 被截断成 28 而少收税，
        // 也保证与银行 percentFloor/percentCeil 的百分比语义一致。
        long taxBps = Math.max(0L, Math.min(10_000L, Math.round(SuiteConfig.shopTaxPercent() * 100.0D)));
        long tax = BankService.percentFloor(gross, taxBps);
        tax = Mth.clamp(tax, 0L, gross);
        long net = gross - tax;
        if (seller != null && !seller.isRemoved()) {
            Economy.deposit(seller, net, "shop_sale");
            ShopData sd = data(seller);
            sd.addTodayIncome(net);
            touch(seller, sd);
            reconcileSlot(seller, entry.slot, false);
            say(seller, "playersuite.shop.msg.sold", itemLabel(toGive), qty,
                    Economy.label(net), Economy.label(tax));
        } else if (net > 0L) {
            Economy.payOffline(server, entry.seller, net, "shop_sale");
        }
        say(buyer, "playersuite.shop.msg.bought", itemLabel(toGive), qty, Economy.label(gross));
        PlayerSuiteMod.LOGGER.debug("[shop] {} 购买 {} 货架#{} 的 {} ×{}，收 {}，税 {}",
                buyer.getGameProfile().getName(), entry.sellerName, entry.slot,
                itemLabel(toGive).getString(), qty, gross, tax);
        return true;
    }

    // ------------------------------------------------------------------ OP 管理

    /** 强制下架（managePermission）：物品转入卖家的「待领取退回」，卖家上线领取。 */
    public static boolean forceUnlist(ServerPlayer actor, UUID sellerId, int slot) {
        if (!actor.hasPermissions(SuiteConfig.managePermission())) {
            say(actor, "playersuite.shop.msg.noPermission", SuiteConfig.managePermission());
            return false;
        }
        return takeOutFor(actor, sellerId, slot, false);
    }

    /** 把货架物品退回卖家（adminPermission）：在线直接塞背包（满则脚下掉落），离线转待领取。 */
    public static boolean returnToSeller(ServerPlayer actor, UUID sellerId, int slot) {
        if (!actor.hasPermissions(SuiteConfig.adminPermission())) {
            say(actor, "playersuite.shop.msg.noPermission", SuiteConfig.adminPermission());
            return false;
        }
        return takeOutFor(actor, sellerId, slot, true);
    }

    private static boolean takeOutFor(ServerPlayer actor, UUID sellerId, int slot, boolean direct) {
        MinecraftServer server = actor.server;
        ShopStorage st = storage(server);
        ShopCatalog cat = catalog(server);
        if (slot < 0 || slot >= SuiteConfig.shopListings()) {
            say(actor, "playersuite.shop.msg.shelfEmpty");
            return false;
        }
        String key = ShopStorage.key(sellerId, slot);
        ShopCatalog.Entry before = cat.find(sellerId, slot);
        String sellerName = before != null ? before.sellerName : null;
        ItemStack taken = st.remove(key);
        cat.remove(sellerId, slot);
        ServerPlayer seller = server.getPlayerList().getPlayer(sellerId);
        if (seller != null && !seller.isRemoved()) {
            ShopData sd = data(seller);
            sd.removeListing(slot);
            touch(seller, sd);
            if (sellerName == null) {
                sellerName = seller.getGameProfile().getName();
            }
        }
        if (sellerName == null) {
            sellerName = sellerId.toString().substring(0, 8);
        }
        if (!taken.isEmpty()) {
            if (direct && seller != null && !seller.isRemoved()) {
                deliver(seller, taken);
                say(seller, "playersuite.shop.msg.returnedNow", itemLabel(taken), taken.getCount());
            } else {
                st.addReturn(sellerId, taken);
                if (seller != null) {
                    say(seller, "playersuite.shop.msg.returnPending", itemLabel(taken), taken.getCount());
                }
            }
        }
        audit(actor, direct ? "playersuite.shop.audit.return" : "playersuite.shop.audit.unlist",
                actor.getGameProfile().getName(), sellerName, slot,
                taken.isEmpty() ? "0" : itemLabel(taken).getString() + " ×" + taken.getCount());
        return true;
    }

    /** 清空商店（adminPermission）：所有托管物品退回卖家（在线背包/掉落，离线待领取）。 */
    public static int clearShop(ServerPlayer actor, ServerPlayer target) {
        if (!actor.hasPermissions(SuiteConfig.adminPermission())) {
            say(actor, "playersuite.shop.msg.noPermission", SuiteConfig.adminPermission());
            return 0;
        }
        MinecraftServer server = actor.server;
        ShopStorage st = storage(server);
        ShopCatalog cat = catalog(server);
        int cleared = 0;
        for (ShopCatalog.Entry entry : cat.entriesOf(target.getUUID())) {
            ItemStack taken = st.remove(entry.storageKey());
            if (!taken.isEmpty()) {
                deliver(target, taken);
            }
            cleared++;
        }
        for (String key : st.keysOf(target.getUUID())) {
            int slot = ShopStorage.slotOfKey(key);
            if (slot >= 0) {
                ItemStack taken = st.remove(key);
                if (!taken.isEmpty()) {
                    deliver(target, taken);
                    cleared++;
                }
            }
        }
        ShopData data = data(target);
        data.clearListings();
        touch(target, data);
        cat.clearOf(target.getUUID());
        st.setDirty();
        say(target, "playersuite.shop.msg.shopClearedBy", actor.getGameProfile().getName(), cleared);
        audit(actor, "playersuite.shop.audit.clear",
                actor.getGameProfile().getName(), target.getGameProfile().getName(), cleared);
        return cleared;
    }

    // ------------------------------------------------------------------ 文本输入

    /** 输入框提交（长度/数字/权限全部重新校验，客户端输入不可信）。 */
    public static void onTextInput(ServerPlayer player, String action, String text) {
        if (action == null || action.isEmpty()) {
            return;
        }
        if (!SuiteConfig.featureEnabled(FeatureOpeners.SHOP)) {
            say(player, "playersuite.shop.msg.featureDisabled");
            return;
        }
        String cleaned = cleanText(text);
        if (ACTION_NAME.equals(action)) {
            ShopData data = data(player);
            data.setShopName(cleaned);
            touch(player, data);
            // 同步目录里的店名
            ShopCatalog cat = catalog(player.server);
            for (ShopCatalog.Entry e : cat.entriesOf(player.getUUID())) {
                e.shopName = data.shopName();
                cat.touch(e);
            }
            say(player, "playersuite.shop.msg.nameSet", data.shopName().isEmpty()
                    ? player.getGameProfile().getName() : data.shopName());
            refresh(player, player.containerMenu instanceof ShopMenu m ? m.page() : 0);
            return;
        }
        if (!action.startsWith(ACTION_PRICE_PREFIX)) {
            return;
        }
        int slot;
        try {
            slot = Integer.parseInt(action.substring(ACTION_PRICE_PREFIX.length()));
        } catch (NumberFormatException e) {
            return;
        }
        if (slot < 0 || slot >= SuiteConfig.shopListings()) {
            return;
        }
        ShopData data = data(player);
        ShopData.Listing listing = data.getListing(slot);
        if (listing == null) {
            say(player, "playersuite.shop.msg.shelfEmpty");
            return;
        }
        long value;
        try {
            value = Long.parseLong(cleaned.trim());
        } catch (NumberFormatException e) {
            say(player, "playersuite.shop.msg.priceBad");
            return;
        }
        long min = SuiteConfig.shopMinPrice();
        long max = SuiteConfig.shopMaxPrice();
        long clamped = Mth.clamp(value, min, max);
        boolean wasClamped = clamped != value;
        listing.price = clamped;
        touch(player, data);
        ShopCatalog cat = catalog(player.server);
        ShopCatalog.Entry entry = cat.find(player.getUUID(), slot);
        if (entry == null) {
            reconcileSlot(player, slot, false);
        } else {
            entry.price = clamped;
            cat.touch(entry);
        }
        say(player, "playersuite.shop.msg.priceSet", Economy.label(clamped));
        if (wasClamped) {
            say(player, "playersuite.shop.msg.priceClamped", Economy.label(min), Economy.label(max));
        }
        refresh(player, player.containerMenu instanceof ShopMenu m ? m.page() : 0);
    }

    // ------------------------------------------------------------------ 登录同步

    /** 玩家上线：领回管理员退回的托管物品，并把货架与托管仓/目录对账。 */
    public static void onLogin(ServerPlayer player) {
        MinecraftServer server = player.server;
        ShopStorage st = storage(server);
        ShopCatalog cat = catalog(server);
        ShopData data = data(player);

        // 1) 管理员退回暂存的物品
        List<ItemStack> returns = st.takeReturns(player.getUUID());
        int returnStacks = 0;
        int returnItems = 0;
        for (ItemStack stack : returns) {
            if (stack.isEmpty()) {
                continue;
            }
            deliver(player, stack);
            returnStacks++;
            returnItems = (int) Math.min(Integer.MAX_VALUE, (long) returnItems + stack.getCount());
        }
        if (returnStacks > 0) {
            player.displayClientMessage(Component.translatable("playersuite.shop.msg.returnsDelivered",
                    returnStacks, returnItems), false);
        }

        // 2) 货架与托管仓对账（被买空/被管理员下架的清理，库存数修正）
        int syncedChanged = 0;
        int syncedOut = 0;
        int slots = SuiteConfig.shopListings();
        for (Map.Entry<Integer, ShopData.Listing> e : List.copyOf(data.sortedListings())) {
            int slot = e.getKey();
            if (slot < 0 || slot >= slots) {
                data.removeListing(slot);
                continue;
            }
            if (st.count(ShopStorage.key(player.getUUID(), slot)) <= 0) {
                data.removeListing(slot);
                cat.remove(player.getUUID(), slot);
                syncedOut++;
            }
        }
        for (int slot = 0; slot < slots; slot++) {
            if (st.count(ShopStorage.key(player.getUUID(), slot)) > 0) {
                if (reconcileSlot(player, slot, false)) {
                    syncedChanged++;
                }
            }
        }
        if (syncedChanged > 0 || syncedOut > 0) {
            player.displayClientMessage(Component.translatable("playersuite.shop.msg.synced",
                    syncedChanged, syncedOut), false);
        }
        data.rollDay();
        touch(player, data);
    }

    // ------------------------------------------------------------------ 工具

    /** 把物品交给在线玩家：先塞背包，剩余掉在脚下。 */
    public static void deliver(ServerPlayer target, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        ItemStack leftover = ItemHandlerHelper.insertItemStacked(
                new InvWrapper(target.getInventory()), stack.copy(), false);
        if (!leftover.isEmpty()) {
            target.drop(leftover, false);
        }
    }

    /** 背包（36 格主包）里与样本同类（物品+组件）的总数。 */
    public static int countInInventory(Player player, ItemStack sample) {
        if (sample.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, sample)) {
                sum = (int) Math.min(Integer.MAX_VALUE, (long) sum + stack.getCount());
            }
        }
        return sum;
    }

    /** 从背包扣除 amount 件同类商品；数量不足时不动（调用前必须先用 countInInventory 检查）。 */
    public static int consumeFromInventory(Player player, ItemStack sample, int amount) {
        if (sample.isEmpty() || amount <= 0 || countInInventory(player, sample) < amount) {
            return 0;
        }
        int remaining = amount;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, sample)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        return amount - remaining;
    }

    /** 剔除格式符与控制字符并 trim。 */
    public static String cleanText(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (esc) {
                esc = false;
                if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                    continue;
                }
            }
            if (c == '\u00A7') {
                esc = true;
                continue;
            }
            if (c < 0x20) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    static void say(ServerPlayer player, String key, Object... args) {
        if (player != null) {
            player.displayClientMessage(Component.translatable(key, args), true);
        }
    }

    /** OP 动作：写进系统消息（存档可查）+ 日志。 */
    static void audit(ServerPlayer actor, String key, Object... args) {
        Component message = Component.translatable(key, args)
                .withStyle(ChatFormatting.GRAY);
        actor.createCommandSourceStack().sendSystemMessage(message);
        PlayerSuiteMod.LOGGER.info("[shop][audit] {}", message.getString());
    }

    // ------------------------------------------------------------------ SavedData 落盘

    /** 服务器启动/关闭时确保两个 SavedData 已创建且脏过一次（NeoForge 会随世界保存）。 */
    public static void flushAll(MinecraftServer server) {
        try {
            storage(server).setDirty();
            catalog(server).setDirty();
        } catch (Throwable t) {
            PlayerSuiteMod.LOGGER.warn("[shop] SavedData 落盘失败：{}", t.getMessage());
        }
    }
}
