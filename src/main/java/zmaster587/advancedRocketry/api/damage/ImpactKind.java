package zmaster587.advancedRocketry.api.damage;

/**
 * What KIND of impact is being declared against structure — the hull layer's own vocabulary.
 *
 * <p>This enum lives in AR's public API and deliberately does <b>not</b> reference the shield mod's
 * {@code ShieldStrikeKind}: a dependent mod that wants to damage a hull must not be forced to import
 * the shield package. The mapping between the two is declared in one place on AR's side.</p>
 *
 * <p>The mapping is <b>many-to-two by design</b>. A shell distinguishes only "physical" from "energy",
 * because that is all its resistance bias needs; a hull cares about more than that — thermal ablation
 * and a solid round are one pair of shield kinds and two entirely different things to structure. New
 * kinds may be added here without the shield layer growing a matching constant.</p>
 */
public enum ImpactKind {

    /** A solid travelling mass: a slug, a round, a thrown body, a collision. */
    KINETIC,

    /** A blast: energy delivered as overpressure across a region rather than along a line. */
    EXPLOSIVE,

    /** Sustained heat: star plasma, a corona, re-entry ablation. */
    THERMAL,

    /** Coherent directed energy: a laser or particle beam. */
    BEAM
}
