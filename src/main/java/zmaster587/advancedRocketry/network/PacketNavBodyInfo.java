package zmaster587.advancedRocketry.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import zmaster587.advancedRocketry.universe.InfoTier;
import zmaster587.advancedRocketry.universe.PlanetInfoField;
import zmaster587.libVulpes.network.BasePacket;

/**
 * The navigation computer's <b>redacted</b> answer about one body: server&rarr;client, carrying only the
 * fields the asking ship has earned the right to see.
 *
 * <p>This is a separate channel from the render sync on purpose. The render feed says where to draw a
 * dot; this one says what the body IS, and every field on it has passed
 * {@code PlanetInfoField.isVisible} on the SERVER. A client cannot leak what it was never sent, so the
 * redaction cannot be defeated by modifying the GUI.</p>
 *
 * <p>Wire contract (same-version, client-bound): {@code writeInt(tierOrdinal)}, {@code writeInt(count)}
 * and then, per field, {@code writeInt(fieldOrdinal)} + {@code writeString(value)}.</p>
 */
public final class PacketNavBodyInfo extends BasePacket {

    /** Client-side store of the last answer received; the nav GUI reads it. */
    private static final Map<PlanetInfoField, String> CLIENT_VIEW = new LinkedHashMap<>();
    /** The tier the last answer was redacted at. */
    private static InfoTier clientTier = InfoTier.TELESCOPE;

    private Map<PlanetInfoField, String> fields = new LinkedHashMap<>();
    private InfoTier tier = InfoTier.TELESCOPE;

    public PacketNavBodyInfo() {
    }

    /** Server factory: the already-redacted view to send. */
    public static PacketNavBodyInfo of(InfoTier tier, Map<PlanetInfoField, String> redacted) {
        PacketNavBodyInfo p = new PacketNavBodyInfo();
        p.tier = tier == null ? InfoTier.TELESCOPE : tier;
        if (redacted != null) {
            p.fields.putAll(redacted);
        }
        return p;
    }

    /** The payload of THIS instance — read back by tests without touching client statics. */
    public Map<PlanetInfoField, String> payload() {
        return fields;
    }

    public InfoTier tier() {
        return tier;
    }

    @Override
    public void write(ByteBuf out) {
        PacketBuffer buffer = new PacketBuffer(out);
        buffer.writeInt(tier.ordinal());
        buffer.writeInt(fields.size());
        for (Map.Entry<PlanetInfoField, String> e : fields.entrySet()) {
            buffer.writeInt(e.getKey().ordinal());
            buffer.writeString(e.getValue() == null ? "" : e.getValue());
        }
    }

    @Override
    public void readClient(ByteBuf in) {
        PacketBuffer buffer = new PacketBuffer(in);
        InfoTier[] tiers = InfoTier.values();
        int tierOrdinal = buffer.readInt();
        tier = tierOrdinal >= 0 && tierOrdinal < tiers.length ? tiers[tierOrdinal] : InfoTier.TELESCOPE;
        PlanetInfoField[] all = PlanetInfoField.values();
        Map<PlanetInfoField, String> decoded = new LinkedHashMap<>();
        int count = buffer.readInt();
        for (int i = 0; i < count; i++) {
            int ordinal = buffer.readInt();
            String value = buffer.readString(512);
            if (ordinal >= 0 && ordinal < all.length) {
                decoded.put(all[ordinal], value);
            }
        }
        fields = decoded;
    }

    @Override
    public void read(ByteBuf in) {
        // never read on the server: this channel only answers
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void executeClient(EntityPlayer player) {
        CLIENT_VIEW.clear();
        CLIENT_VIEW.putAll(fields);
        clientTier = tier;
    }

    @Override
    public void executeServer(EntityPlayerMP player) {
    }

    /** The last redacted body view the client received, in reading order. */
    @SideOnly(Side.CLIENT)
    public static List<String> clientLines() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<PlanetInfoField, String> e : CLIENT_VIEW.entrySet()) {
            lines.add(e.getKey().name() + ": " + e.getValue());
        }
        return Collections.unmodifiableList(lines);
    }

    /** The tier the client's current view was redacted at. */
    @SideOnly(Side.CLIENT)
    public static InfoTier clientTier() {
        return clientTier;
    }
}
