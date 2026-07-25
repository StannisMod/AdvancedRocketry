package com.github.stannismod.affs.world.shield;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A <b>priority group</b> (D134-5): a player-named bucket of emitters that share a redistribution
 * priority. Editing the group pushes its priority into every member emitter — the <em>setting</em> stays
 * emitter-owned (so it survives losing the console), the <em>group</em> is only the named selection that
 * writes it.
 *
 * <p><b>A group is not a zone.</b> The physics zone is the per-emitter Voronoi surface partition
 * (D134-3); a group is a control bucket whose members may be scattered anywhere on the hull. Grouping
 * emitters does not make their shield coverage contiguous.</p>
 */
public final class ShieldPriorityGroup {

    private final String name;
    private int priority;
    private final Set<BlockPos> members = new LinkedHashSet<>();

    public ShieldPriorityGroup(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    public String getName() {
        return name;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean addMember(BlockPos pos) {
        return pos != null && members.add(pos);
    }

    public boolean removeMember(BlockPos pos) {
        return pos != null && members.remove(pos);
    }

    public boolean hasMember(BlockPos pos) {
        return pos != null && members.contains(pos);
    }

    public Set<BlockPos> getMembers() {
        return Collections.unmodifiableSet(members);
    }

    public int getMemberCount() {
        return members.size();
    }

    public NBTTagCompound writeToNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("name", name);
        tag.setInteger("priority", priority);
        NBTTagList list = new NBTTagList();
        for (BlockPos member : members) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("x", member.getX());
            entry.setInteger("y", member.getY());
            entry.setInteger("z", member.getZ());
            list.appendTag(entry);
        }
        tag.setTag("members", list);
        return tag;
    }

    public static ShieldPriorityGroup readFromNBT(NBTTagCompound tag) {
        if (tag == null) {
            return null;
        }
        String name = tag.getString("name");
        if (name == null || name.isEmpty()) {
            return null;
        }
        ShieldPriorityGroup group = new ShieldPriorityGroup(name, tag.getInteger("priority"));
        NBTTagList list = tag.getTagList("members", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            group.addMember(new BlockPos(entry.getInteger("x"), entry.getInteger("y"), entry.getInteger("z")));
        }
        return group;
    }
}
