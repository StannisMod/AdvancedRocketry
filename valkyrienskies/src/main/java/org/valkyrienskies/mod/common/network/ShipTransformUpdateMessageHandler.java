package org.valkyrienskies.mod.common.network;

import net.minecraft.client.Minecraft;
import net.minecraft.util.IThreadListener;
import net.minecraft.util.Tuple;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.valkyrienskies.mod.common.ships.QueryableShipData;
import org.valkyrienskies.mod.common.ships.ShipData;
import org.valkyrienskies.mod.common.ships.interpolation.ITransformInterpolator;
import org.valkyrienskies.mod.common.ships.ship_transform.ShipTransform;
import org.valkyrienskies.mod.common.ships.ship_world.IPhysObjectWorld;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;
import org.valkyrienskies.mod.common.util.ValkyrienUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class ShipTransformUpdateMessageHandler implements IMessageHandler<ShipTransformUpdateMessage, IMessage> {

    @Override
    @SuppressWarnings("Convert2Lambda")
    // Why do you not use a lambda? Because lambdas are compiled and this causes NoClassDefFound
    // errors. DON'T USE A LAMBDA
    public IMessage onMessage(final ShipTransformUpdateMessage message, final MessageContext ctx) {
        IThreadListener mainThread = Minecraft.getMinecraft();
        mainThread.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                World world = Minecraft.getMinecraft().world;
                if (world == null || world.provider.getDimension() != message.getDimensionID()) {
                    // The poses in this packet belong to a world this client is no longer in. It is
                    // addressed to a dimension and carries no other way to tell, and a ship keeps its
                    // identity when it crosses between worlds - so applying it against whatever world
                    // the client is in now can put the SOURCE world's pose on the ship that just
                    // arrived here. The window is a dimension change with a packet still in flight.
                    return;
                }
                IPhysObjectWorld physObjectWorld = ValkyrienUtils.getPhysObjWorld(world);
                QueryableShipData worldData = QueryableShipData.get(world);

                for (Map.Entry<UUID, Tuple<ShipTransform, AxisAlignedBB>> transformUpdate : message.shipTransforms.entrySet()) {
                    final UUID shipID = transformUpdate.getKey();
                    final ShipTransform shipTransform = transformUpdate.getValue().getFirst();
                    final AxisAlignedBB shipBB = transformUpdate.getValue().getSecond();

                    final PhysicsObject physicsObject = ValkyrienUtils.getPhysObjWorld(world).getPhysObjectFromUUID(shipID);
                    if (physicsObject != null) {
                        // Do not update the transform in ShipData, that will be done by PhysicsObject.tick()
                        ITransformInterpolator interpolator = physicsObject.getTransformInterpolator();
                        interpolator.onNewTransformPacket(shipTransform, shipBB);
                        // Flight recorder: this is the ONLY place a client learns where a ship is, and
                        // the pose the pilot sees is a filter chasing it. Counting arrivals here is
                        // what separates "the ship moved unevenly" from "its pose arrived unevenly".
                        zmaster587.advancedRocketry.util.MotionTrace.clientShipTransformUpdates++;
                    }
                }
            }
        });

        return null;
    }
}
