package zmaster587.advancedRocketry.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import zmaster587.advancedRocketry.util.RocketGuiNavigation;
import zmaster587.libVulpes.network.BasePacket;

public class PacketBackToRocketGui extends BasePacket {

    private int sourceTileDimensionId;
    private int sourceTileX;
    private int sourceTileY;
    private int sourceTileZ;

    public PacketBackToRocketGui() {}

    public PacketBackToRocketGui(int sourceTileDimensionId, BlockPos sourceTilePos) {
        this.sourceTileDimensionId = sourceTileDimensionId;
        this.sourceTileX = sourceTilePos.getX();
        this.sourceTileY = sourceTilePos.getY();
        this.sourceTileZ = sourceTilePos.getZ();
    }

    @Override
    public void write(ByteBuf out) {
        out.writeInt(sourceTileDimensionId);
        out.writeInt(sourceTileX);
        out.writeInt(sourceTileY);
        out.writeInt(sourceTileZ);
    }

    @Override
    public void read(ByteBuf in) {
        sourceTileDimensionId = in.readInt();
        sourceTileX = in.readInt();
        sourceTileY = in.readInt();
        sourceTileZ = in.readInt();
    }

    @Override
    public void readClient(ByteBuf in) {
        read(in);
    }

    @Override
    public void executeClient(EntityPlayer player) {
        // Serverbound packet only.
    }

    @Override
    public void executeServer(EntityPlayerMP player) {
        RocketGuiNavigation.openRocketGuiFromReturnContext(
                player,
                sourceTileDimensionId,
                new BlockPos(sourceTileX, sourceTileY, sourceTileZ)
        );
    }
}