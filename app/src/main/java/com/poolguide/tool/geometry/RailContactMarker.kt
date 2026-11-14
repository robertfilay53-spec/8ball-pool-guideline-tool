package com.poolguide.tool.geometry

/**
 * Rail-contact markers (v1.0.16). Pure Kotlin, no Android dependency —
 * fully unit-testable on the JVM.
 *
 * A bank or kick shot draws as a bent path, but until now nothing
 * marked WHERE on the cushion the ball must hit: the player saw the
 * bend and had to guess the exact spot. The marker is a small ring
 * at each rail contact, in the shot family's colour (cyan for banks,
 * magenta for kicks), so the player aims at a visible spot, not just
 * a line.
 *
 * The contacts need no new solver data. The solver builds every
 * bank/kick path as connected segments whose shared vertices ARE the
 * rail contacts: BankSolution.bankPath is target -> contact ->
 * pocket, TwoRailBankSolution.bankPath is target -> contact 1 ->
 * contact 2 -> pocket, and the kick paths are the same shape for the
 * cue ball (cue -> contact(s) -> ghost). GeometryTest pins this for
 * every family (each test asserts the path joints equal
 * railContact / railContacts), and the contact16 probe re-verified
 * it against the solver before this file was written. So the contacts
 * of any bank/kick path are its interior vertices: every segment end
 * except the last segment's end (the pocket / ghost).
 *
 * A direct path (one segment) has no interior vertex and no marker.
 * A path with fewer than two segments has none either. The caller
 * must pass a connected path — the solver always builds one; an
 * unconnected list would still return its segment ends, so do not
 * feed this arbitrary segments.
 */
object RailContactMarker {

    /** Rail contacts of a bank/kick path, in path order: the interior
     * vertices where the ball's path bends off a cushion. Empty for a
     * direct (single-segment) path. */
    fun contacts(path: List<Segment>): List<Vec2> =
        if (path.size < 2) emptyList()
        else path.dropLast(1).map { it.end }
}
