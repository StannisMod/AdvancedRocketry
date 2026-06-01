package zmaster587.advancedRocketry.integration.waila;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;
import zmaster587.advancedRocketry.integration.dataloaders.AbstractDataContext;

public class WailaDataContext extends AbstractDataContext {

    List<String> tooltips;

    public WailaDataContext(List<String> tooltips) {
        this.tooltips = tooltips;
    }

	@Override
	public void addMessage(String message, TextFormatting formatting) {
		tooltips.add(formatting + message + TextFormatting.RESET);
	}

	@Override
	public void addProgressBar(@Nullable String message, int amount, int capacity, int border, int background, int filled, int altFilled, String suffix) {
		String text = amount + "/" + capacity + " " + suffix;
		if (message != null) {
			text = message + " (" + text + ")";
		}
        addMessage(text);
	}

	@Override
	public void pushStack(ItemStack stack) {
		// waila does not support item rendering
	}

	@Override
	public void popStack() {
		// waila does not support item rendering
	}

	@Override
	public String translate(String key) {
		// This will only be called on the client, so we can directly use i18n.translate
		return I18n.format(key);
	}

	@Override
	public boolean supportsRichData() {
		return false;
	}
    
}
