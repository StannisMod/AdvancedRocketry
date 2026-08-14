package zmaster587.advancedRocketry.damage;

import com.github.stannismod.affs.world.shield.ShieldStrikeKind;
import zmaster587.advancedRocketry.api.damage.ImpactKind;

/**
 * The single declared mapping from the hull's impact kinds to the shield's two.
 *
 * <p>It lives here rather than on {@link ImpactKind} itself so that AR's public API does not drag the
 * shield package in behind it: a mod that only wants to damage a hull should not have to know a shield
 * mod exists. The mapping is many-to-two on purpose and will stay that way — the hull may grow kinds
 * the shell has no opinion about, and every one of them still has to be billable by a shell.</p>
 */
public final class ImpactKindMapping {

    private ImpactKindMapping() {
    }

    /**
     * How a shell bills this kind of impact. Physical for anything that arrives as matter or blast,
     * energy for anything that arrives as radiation — the only distinction a resistance bias makes.
     */
    public static ShieldStrikeKind toShieldKind(ImpactKind kind) {
        if (kind == null) {
            return ShieldStrikeKind.KINETIC;
        }
        switch (kind) {
            case KINETIC:
            case EXPLOSIVE:
                return ShieldStrikeKind.KINETIC;
            case THERMAL:
            case BEAM:
                return ShieldStrikeKind.RADIANT;
            default:
                throw new IllegalArgumentException("no shield billing declared for impact kind " + kind
                        + " — every hull kind must state how a shell charges for it");
        }
    }
}
