package com.vt.vt.cleaner;

import com.vt.vt.VTMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public class SharedTrashInventory extends SimpleContainer {
    private final File sourceFile;
    private final HolderLookup.Provider lookupProvider;

    public SharedTrashInventory(List<ItemStack> items, File sourceFile, HolderLookup.Provider lookup) {
        super(items.toArray(new ItemStack[0]));
        this.sourceFile = sourceFile;
        this.lookupProvider = lookup;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        saveToFile();
    }

    /**
     * 安全寫入硬碟：先寫入暫存檔再進行原子替換，防止伺服器中途關閉造成存檔毀損
     */
    private synchronized void saveToFile() {
        try {
            CompoundTag root = new CompoundTag();
            ListTag listTag = new ListTag();
            for (int i = 0; i < this.getContainerSize(); i++) {
                ItemStack stack = this.getItem(i);
                if (!stack.isEmpty()) {
                    listTag.add(stack.save(lookupProvider));
                }
            }
            root.put("Items", listTag);
            root.putInt("TotalCount", listTag.size());

            Path targetPath = sourceFile.toPath();
            Path tempPath = targetPath.resolveSibling(sourceFile.getName() + ".tmp");

            NbtIo.writeCompressed(root, tempPath);
            Files.move(tempPath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            VTMod.LOGGER.error("[VT/Cleaner] Failed to save rollback inventory file: {}", sourceFile.getName(), e);
        }
    }
}