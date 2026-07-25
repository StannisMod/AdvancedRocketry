package com.github.stannismod.affs.world.shield;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The authoritative shield configuration of one domain (D134-6 Layer 4): the priority groups a player
 * defined for a hull or base. <b>SSOT on the data, replicated access to it</b> — this object is the one
 * authoritative copy, and every console in the domain reads and edits <em>it</em>, so consoles are
 * stateless editors: destroying one loses nothing.
 *
 * <p>Zero groups is the floor and the default: no console, no grouping ⇒ one implicit uniform group ⇒
 * a working shield with every emitter at its own default priority.</p>
 */
public final class ShieldDomainConfig {

    private final String domainId;
    private final Map<String, ShieldPriorityGroup> groups = new LinkedHashMap<>();

    public ShieldDomainConfig(String domainId) {
        this.domainId = domainId;
    }

    public String getDomainId() {
        return domainId;
    }

    public Collection<ShieldPriorityGroup> getGroups() {
        return new ArrayList<>(groups.values());
    }

    public ShieldPriorityGroup getGroup(String name) {
        return name == null ? null : groups.get(name);
    }

    public int getGroupCount() {
        return groups.size();
    }

    /** Creates the group if absent; returns the existing one otherwise (idempotent, so any console may call it). */
    public ShieldPriorityGroup createGroup(String name, int priority) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        ShieldPriorityGroup existing = groups.get(name);
        if (existing != null) {
            return existing;
        }
        ShieldPriorityGroup group = new ShieldPriorityGroup(name, priority);
        groups.put(name, group);
        return group;
    }

    /**
     * Deletes a group. The members' own priority setting is left as it was — the setting is
     * emitter-owned (D134-5), so removing the naming layer never silently re-tunes the shield.
     */
    public boolean deleteGroup(String name) {
        return name != null && groups.remove(name) != null;
    }

    /** The group that lists this emitter, or null when it belongs to none (the implicit default group). */
    public ShieldPriorityGroup findGroupOf(BlockPos pos) {
        for (ShieldPriorityGroup group : groups.values()) {
            if (group.hasMember(pos)) {
                return group;
            }
        }
        return null;
    }

    /** An emitter belongs to at most one group, so joining a new group leaves any previous one. */
    public void assignMember(String groupName, BlockPos pos) {
        if (pos == null) {
            return;
        }
        for (ShieldPriorityGroup group : groups.values()) {
            group.removeMember(pos);
        }
        ShieldPriorityGroup target = groups.get(groupName);
        if (target != null) {
            target.addMember(pos);
        }
    }

    public NBTTagCompound writeToNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("domainId", domainId);
        NBTTagList list = new NBTTagList();
        for (ShieldPriorityGroup group : groups.values()) {
            list.appendTag(group.writeToNBT());
        }
        tag.setTag("groups", list);
        return tag;
    }

    public static ShieldDomainConfig readFromNBT(NBTTagCompound tag) {
        if (tag == null) {
            return null;
        }
        String domainId = tag.getString("domainId");
        if (domainId == null || domainId.isEmpty()) {
            return null;
        }
        ShieldDomainConfig config = new ShieldDomainConfig(domainId);
        NBTTagList list = tag.getTagList("groups", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            ShieldPriorityGroup group = ShieldPriorityGroup.readFromNBT(list.getCompoundTagAt(i));
            if (group != null) {
                config.groups.put(group.getName(), group);
            }
        }
        return config;
    }

    /** Group names, in creation order — what a console lists. */
    public List<String> getGroupNames() {
        return new ArrayList<>(groups.keySet());
    }
}
