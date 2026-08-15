package zmaster587.advancedRocketry.api.util;

import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.AreaBlob;
import zmaster587.libVulpes.util.HashedBlockPosition;

public interface IBlobHandler {
	/* *
	 * Called when a block is being removed from a blob
	 * @param pos BlockPosition to remove
	 * /
	public void onBlobRemove(BlockPosition pos);
	
	
	/**
	 * Called when a block is being added to the blob
	 * @param pos BlockPosition to add
	 
	public void onBlobAdd(BlockPosition pos);*/

    /**
     * @return true if a blob is allowed to form otherwise false
     */
    boolean canFormBlob();


    //Screw you too for changing it back

    /**
     * Due to Minecraft's obf code we need a different method name than getWorld()
     *
     * @return
     */
    World getWorldObj();

    /**
     * Called when two blobs of the same type overlap
     *
     * @param blockPosition Position at which the overlap occurs
     * @param blob          areaBlob to overlap
     * @return true if the two blobs are allowed to overlap, false if not
     */
    boolean canBlobsOverlap(HashedBlockPosition blockPosition, AreaBlob blob);

    /**
     * @return the maximum distance an object can maintain a blob
     */
    int getMaxBlobRadius();

    /**
     * @return the position to use as root
     */
    HashedBlockPosition getRootPosition();

    int getTraceDistance();

    /**
     * Whether this handler is currently keeping its zone's air alive — sealed, powered and
     * supplying gas — as opposed to merely owning a zone that exists.
     * <p>
     * Life support asks this before it moves a zone's gases or republishes the atmosphere derived
     * from them, so the answer is what separates a room the system is responsible for from a
     * planet's own air that merely happens to be enclosed. It is deliberately a question about the
     * MACHINE and not about the atmosphere currently published: an answer read off the published
     * value cannot tell "we put it there" from "it was already like that", and latches the moment
     * the two stop agreeing.
     * <p>
     * Defaults to false: a handler that has not opted in is not something life support drives.
     */
    default boolean isMaintainingAtmosphere() {
        return false;
    }
}
