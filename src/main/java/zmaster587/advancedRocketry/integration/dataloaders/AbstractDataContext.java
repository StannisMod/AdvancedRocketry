package zmaster587.advancedRocketry.integration.dataloaders;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;

/**
 * Represents a context such as a Waila/TOP tooltip that data can be added to.
 */
public abstract class AbstractDataContext {
    public abstract void addMessage(String message, TextFormatting formatting);
    public abstract void addProgressBar(@Nullable String message, int amount, int capacity, int border, int background, int filled, int altFilled, String suffix);
    public abstract void pushStack(ItemStack stack);
    public abstract void popStack();
    public abstract boolean supportsRichData();

    /**
     * This method will be called on the side where the data is collected.
     * Whether it's the server or the client depends on the mod (Waila = client, TOP = server).
     * @param message the message to translate
     * @return the translated message
     */
    public abstract String translate(String message);

    public void addMessage(String message) {
        addMessage(message, TextFormatting.RESET);
    }
}
