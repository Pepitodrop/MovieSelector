# piet-core

A real, standard-instruction [Piet](https://esolangs.org/wiki/Piet) interpreter and program
generator, in pure Kotlin (JVM), used by the Movie Selector game engine to run actual session
logic (shuffle, accept, reject, undo, exhaustion) as Piet programs — not as decoration.

## What's real Piet here

- All 20 standard codel colors (`PietColor`), exact palette hex values.
- All 18 real instructions (`PietOp`), from the standard hue/lightness delta table.
- DP (4 directions) and CC (2 choosers), with the spec's corner-selection rule for picking an
  exit codel, white-codel sliding, and the 8-consecutive-failures halt rule.
- A real PNG codec (`PietImage`): every generated program is written out as an actual `.png`
  Piet image, and the interpreter can decode and run any standard-palette Piet PNG, not just
  ones this project generated.

## The generator (`PietGridBuilder` / `Track`)

Hand-drawing Piet images doesn't scale, so `PietGridBuilder` assembles them programmatically
using the same idioms real Piet compilers use:

- **Numeric literals**: Piet pushes the *size of the block you just left*, so `push(n)` grows
  the current color block to `n` codels before transitioning out via the PUSH delta. Piet can
  only push positive sizes — there is no way to push a literal `0` directly; build it with
  `push(1)` followed by `NOT`.
- **Branching** (`Track.fork`): `pointer` rotates DP by a popped value (0 = keep going, 1 = turn
  clockwise). `fork` emits the `pointer` instruction, keeps the original track as the "0" arm,
  and returns a new track for the "1" arm, connected through a one-codel white buffer so the new
  arm can start from any color without it implying an operation.

## Termination and the output protocol — read this before adding a new session program

Piet has exactly one non-explicit halt condition: **8 consecutive failed exit attempts** (every
DP/CC combination blocked). A plain straight run of instructions is *not* automatically safe
under this rule: once execution falls off the drawn end, rotating DP through all 4 directions
can rediscover a **previous** instruction cell (there's real, non-black program right next to
it), so the interpreter legitimately walks back into its own history and re-executes it. Two
mitigations are built into the interpreter itself:

1. **Full-state cycle detection.** If `(position, DP, CC, stack)` ever repeats exactly, the
   program can provably never do anything new again, so `PietInterpreter` halts right there
   (`PietTerminationReason.BLOCKED`). This safely resolves the simple case of a single straight
   track bouncing back and forth.
2. **`maxSteps` safety cap** (`PietTerminationReason.STEP_LIMIT_EXCEEDED`), for anything that
   still doesn't settle (e.g. a program whose stack keeps growing, so no exact state ever
   repeats).

Neither mitigation prevents every failure mode. In particular, **a `fork`'s two arms share one
pointer codel** — after the taken arm exhausts itself and bounces back through that shared
codel, the *other* arm's entrance is just as valid a neighbor as anything else, so execution can
(correctly, per the spec) spill into the arm that was *not* supposed to run, appending its
output too.

**The fix is at the protocol level, not the geometry level:** every generated program prints its
real answer first via `out(number)`/`out(char)`. Callers must read only the first *N* values
they expect from `PietResult.outputNumbers` (or a length-bounded prefix of `.output`) and ignore
anything after — never treat "the interpreter eventually stopped" as the signal that the output
is complete. [`de.luisbenedikt.movieselector.game`](../game-engine) follows this contract for
every session operation; see its tests for the exact number of output values each Piet program
is defined to produce.
