package zmaster587.advancedRocketry.integration.theoneprobe;

import java.util.Stack;

import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.NumberFormat;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;
import zmaster587.advancedRocketry.integration.dataloaders.AbstractDataContext;

public class TOPDataContext extends AbstractDataContext {

    Stack<IProbeInfo> probeStack;

    public TOPDataContext(IProbeInfo probeInfo) {
        this.probeStack = new Stack<>();
        this.probeStack.add(probeInfo);
    }

    @Override
    public void addMessage(String message, TextFormatting formatting) {
        current().text(formatting + message + TextFormatting.RESET);
    }

    @Override
    public void addProgressBar(String message, int amount, int capacity, int border, int background, int filled, int altFilled, String suffix) {
        IProbeInfo probeInfo = current();

        if (message != null) {
            probeInfo.text(message);
        }
        probeInfo.progress(
            amount,
            capacity,
            probeInfo.defaultProgressStyle()
                    .borderColor(border)
                    .backgroundColor(background)
                    .filledColor(filled)
                    .alternateFilledColor(altFilled)
                    .height(12)
                    .width(100)
                    .showText(true)
                    .suffix(" " + suffix)
                    .numberFormat(NumberFormat.COMMAS)
        );
    }

    private IProbeInfo current() {
        return this.probeStack.peek();
    }

    @Override
    public void pushStack(ItemStack stack) {
        IProbeInfo row = current().horizontal();
        row.item(stack, current().defaultItemStyle().width(16).height(16));
        this.probeStack.push(row);
    }

    @Override
    public void popStack() {
        this.probeStack.pop();
    }

    @Override
    public String translate(String key) {
        // this runs on the server side
        return IProbeInfo.STARTLOC + key + IProbeInfo.ENDLOC;
    }

	@Override
	public boolean supportsRichData() {
		return true;
	}
    
}
