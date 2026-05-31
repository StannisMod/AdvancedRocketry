package zmaster587.advancedRocketry.integration.dataloaders;

public abstract class WirelessTransceiverDataLoader {
    public abstract boolean isLinked();
    public abstract boolean isExtracting();
    public abstract int getNetworkId();

    public void addWirelessDataInfo(AbstractDataContext context) {
        boolean linked = isLinked();

        context.addMessage(
                getColoredModeText(context, isExtracting())
                        + net.minecraft.util.text.TextFormatting.RESET
                        + "    "
                        + getLinkStatusBadge(context, linked)
        );

        if (linked) {
            context.addMessage(getNetworkIdText(context, getNetworkId()));
        }
    }

    private String getLinkStatusBadge(AbstractDataContext context, boolean linked) {
        return (linked
                ? net.minecraft.util.text.TextFormatting.GREEN
                : net.minecraft.util.text.TextFormatting.RED)
                + context.translate(linked
                ? "msg.top.advancedrocketry.data.link.linked"
                : "msg.top.advancedrocketry.data.link.unlinked");
    }

    private String getNetworkIdText(AbstractDataContext context, int networkId) {
        return net.minecraft.util.text.TextFormatting.GRAY
                + context.translate("msg.top.advancedrocketry.data.network")
                + ": "
                + net.minecraft.util.text.TextFormatting.YELLOW
                + Integer.toString(networkId);
    }

    private String getColoredModeText(AbstractDataContext context, boolean extractMode) {
        return (extractMode
                ? net.minecraft.util.text.TextFormatting.GOLD
                : net.minecraft.util.text.TextFormatting.AQUA)
                + context.translate(extractMode
                ? "msg.top.advancedrocketry.data.mode.extract"
                : "msg.top.advancedrocketry.data.mode.insert");
    }

}
