package dev.mcmods.playersuite.economy;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 「已经发放过初始资金」的玩家集合（全局 {@link SavedData}，永不主动清空）。
 *
 * <p>为什么不能直接用钱包附件判断首次进服：{@link WalletData#isBlank()} 为真时序列化器返回 null，
 * playerdata 里不保留钱包数据；玩家把余额花到 0 后重登，{@code getExistingData(WALLET).isEmpty()}
 * 又会成立，于是初始资金被重复发放（可无限刷）。这里用一个独立的、只增不减的标记集合堵住该漏洞。
 */
public final class InitialGrants extends SavedData {
    private static final String NAME = "playersuite_initial_grants";
    private static final String TAG_PLAYERS = "Players";
    private static final String TAG_ID = "Id";

    public static final SavedData.Factory<InitialGrants> FACTORY =
            new SavedData.Factory<>(InitialGrants::new, InitialGrants::load);

    private final Set<UUID> granted = new HashSet<>();

    public InitialGrants() {
    }

    private static InitialGrants load(CompoundTag tag, HolderLookup.Provider provider) {
        InitialGrants data = new InitialGrants();
        if (tag.contains(TAG_PLAYERS, Tag.TAG_LIST)) {
            ListTag list = tag.getList(TAG_PLAYERS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag one = list.getCompound(i);
                if (one.hasUUID(TAG_ID)) {
                    data.granted.add(one.getUUID(TAG_ID));
                }
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (UUID id : granted) {
            CompoundTag one = new CompoundTag();
            one.putUUID(TAG_ID, id);
            list.add(one);
        }
        tag.put(TAG_PLAYERS, list);
        return tag;
    }

    public static InitialGrants of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** 标记为已发放；返回 true 表示本次是首次标记（调用方据此决定是否真的发钱）。 */
    public boolean markGranted(UUID player) {
        if (player == null) {
            return false;
        }
        if (granted.add(player)) {
            setDirty();
            return true;
        }
        return false;
    }

    /** 是否已经发放过。 */
    public boolean isGranted(UUID player) {
        return player != null && granted.contains(player);
    }
}
