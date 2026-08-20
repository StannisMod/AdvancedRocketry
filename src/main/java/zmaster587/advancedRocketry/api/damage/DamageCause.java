package zmaster587.advancedRocketry.api.damage;

/**
 * What happened to a unit, as opposed to how much of it happened.
 *
 * <h3>This list is deliberately OPEN, and nothing may depend on it being complete</h3>
 * <p>New members are added without ceremony, and a unit that meets one it does not recognise treats it
 * as <em>something happened</em> — never as an error, never as a reason to do nothing. A {@code switch}
 * over these values without a default is a bug waiting for the next member; write the default first.</p>
 *
 * <p>Presence in a nebula is a named likely member that does not exist yet. It is mentioned here so
 * that the absence reads as "not built" rather than as "not thought of".</p>
 *
 * <h3>Why this sits ABOVE {@link ImpactKind} rather than beside it</h3>
 * <p>{@code ImpactKind} answers <em>what kind of thing struck structure</em> — kinetic, thermal,
 * explosive — and it only makes sense when something struck along a line. A hyperspace window
 * collapsing is not an impact of any kind, and forcing it to name one would make every reader of the
 * kind field ask which lie was told. So the cause names the EVENT, and the kind rides along only when
 * there was a body.</p>
 */
public enum DamageCause {

    /** Something arrived along a line and spent a budget: a shell, a bolt, a beam. Carries a kind. */
    IMPACT,

    /** Two structures met at speed. Carries a kind; the geometry is the collision's, not a weapon's. */
    COLLISION,

    /** A hull put down harder than it should have been. */
    HARD_LANDING,

    /** A blast in the world, this unit inside it. */
    EXPLOSION,

    /** A jump ended the way nobody wanted. Hull-wide by nature, and has no point. */
    HYPERSPACE_EXIT,

    /** Accrued use rather than an event — the wear channel, which has always had its own writer. */
    WEAR
}
