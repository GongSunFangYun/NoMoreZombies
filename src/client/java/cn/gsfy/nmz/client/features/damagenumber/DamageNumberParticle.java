package cn.gsfy.nmz.client.features.damagenumber;

import cn.gsfy.nmz.client.utils.JavaUtils;

import java.util.concurrent.ThreadLocalRandom;

/**
 * One damage/heal floating number. Its text, colour, animation, world
 * position, and lifetime all live on this one object.
 *
 * <p>Lifetime counts in client ticks; on reaching zero
 * {@link DamageNumberTracker} removes it. Position is detached from the
 * entity at the moment of creation, so the number keeps floating through
 * its animation even after the entity dies or unloads. Text, colour, and
 * animation are all fixed by the factory at construction; the renderer
 * only reads them.
 */
public final class DamageNumberParticle {

    /** Lifetime in client ticks. */
    private static final int LIFESPAN = 60;

    /** Damage colour (red). Every damage number uses it, no fading by
     *  remaining-health ratio. */
    private static final int COLOUR_DAMAGE = 0xFFFF5555;

    /** Heal colour (green). */
    private static final int COLOUR_HEAL = 0xFF55FF55;

    /** Decimal places to keep. Health is often fractional; keep one place,
     *  then trim trailing zeros. */
    private static final int DECIMALS = 1;

    /**
     * Animation direction. POP_OFF arcs up then falls (damage); RISE lifts
     * a little then floats up decelerating (heal).
     *
     * <p>Affects only initial velocity and per-tick correction; it plays no
     * part in colour or text.
     */
    private enum Animation {
        POP_OFF,
        RISE
    }

    private final Animation animation;
    private final String text;
    private final int colour;

    private float prevX;
    private float prevY;
    private float prevZ;
    private float x;
    private float y;
    private float z;
    /** Three-axis initial velocity, set once at construction per the
     *  animation. X and Z never change afterwards; per-tick updates touch
     *  only Y. */
    private final float velocityX;
    private float velocityY;
    private final float velocityZ;
    private int life;

    /**
     * Sets colour and text, then places the starting position and initial
     * velocity per the animation.
     *
     * <p>Position is placed before it is snapshotted into prev, so the
     * first frame's interpolation origin is the spawn point—the number
     * never flies in from (0,0,0).
     *
     * @param animation animation direction
     * @param x spawn X (world coordinates, usually the entity's eye height)
     * @param y spawn Y
     * @param z spawn Z
     * @param amount health change; the absolute value goes into the text
     * @throws IllegalStateException when the animation enum gains a value
     *   this class does not handle
     */
    private DamageNumberParticle(Animation animation, float x, float y, float z, double amount) {
        this.animation = animation;
        this.text = JavaUtils.roundToDecimal(amount, DECIMALS);
        this.colour = animation == Animation.POP_OFF ? COLOUR_DAMAGE : COLOUR_HEAL;
        this.life = LIFESPAN;
        this.x = x;
        this.y = y;
        this.z = z;

        ThreadLocalRandom random = ThreadLocalRandom.current();

        if (animation == Animation.RISE) {
            // RISE's lift comes before the initial velocity: it moves the
            // spawn point, not the speed.
            this.x += (float) random.nextGaussian() * 0.05f;
            this.y += 0.4f;
            this.z += (float) random.nextGaussian() * 0.05f;
        }

        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;

        switch (animation) {
            case POP_OFF -> {
                this.velocityX = (float) random.nextGaussian() * 0.03f + 0.025f;
                this.velocityY = random.nextFloat() * 0.035f + 0.37f;
                this.velocityZ = (float) random.nextGaussian() * 0.03f + 0.025f;
            }
            case RISE -> {
                this.velocityX = 0.0f;
                this.velocityY = 0.2f;
                this.velocityZ = 0.0f;
            }
            default -> throw new IllegalStateException("未处理的飘字动画: " + animation);
        }
    }

    /**
     * Builds a damage number (red, arcs up then falls).
     *
     * @param x spawn X
     * @param y spawn Y
     * @param z spawn Z
     * @param amount damage dealt; the absolute value is used
     * @return a new number
     */
    public static DamageNumberParticle damage(float x, float y, float z, double amount) {
        return new DamageNumberParticle(Animation.POP_OFF, x, y, z, amount);
    }

    /**
     * Builds a heal number (green, floats up decelerating).
     *
     * @param x spawn X
     * @param y spawn Y
     * @param z spawn Z
     * @param amount health restored; the absolute value is used
     * @return a new number
     */
    public static DamageNumberParticle heal(float x, float y, float z, double amount) {
        return new DamageNumberParticle(Animation.RISE, x, y, z, amount);
    }

    /**
     * Advances one tick: lifetime decrements, the current position is
     * snapshotted, then the number moves one step per the animation.
     *
     * <p>The snapshot has to come before the move—the renderer aligns to
     * the frame rate by interpolating between prev and the current value.
     *
     * @return whether the number is still alive (lifetime not yet up)
     */
    public boolean tick() {
        this.life--;
        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;

        switch (this.animation) {
            case POP_OFF -> {
                this.velocityY -= 0.05f;
                this.x += this.velocityX;
                this.y += this.velocityY;
                this.z += this.velocityZ;
            }
            case RISE -> {
                this.velocityY -= 0.02f;
                if (this.velocityY < 0.0f) {
                    // Y only: once the rise tops out, hover in place rather
                    // than drifting sideways.
                    this.velocityY *= 0.5f;
                }
                this.x += this.velocityX;
                this.y += this.velocityY;
                this.z += this.velocityZ;
            }
            default -> throw new IllegalStateException("未处理的飘字动画: " + this.animation);
        }

        return this.life >= 0;
    }

    /**
     * Interpolates between the previous and current position by the
     * within-frame progress—frame rate is higher than tick rate, and
     * without interpolation the number jumps grid by grid.
     *
     * @param tickDelta within-frame tick delta, 0–1
     * @return interpolated X
     */
    public float lerpX(float tickDelta) {
        return this.prevX + (this.x - this.prevX) * tickDelta;
    }

    /** Interpolated Y (same contract as {@link #lerpX(float)}). */
    public float lerpY(float tickDelta) {
        return this.prevY + (this.y - this.prevY) * tickDelta;
    }

    /** Interpolated Z (same contract as {@link #lerpX(float)}). */
    public float lerpZ(float tickDelta) {
        return this.prevZ + (this.z - this.prevZ) * tickDelta;
    }

    /** Text to draw (bare number, no unit or sign). */
    public String text() {
        return this.text;
    }

    /** Text colour (ARGB). */
    public int colour() {
        return this.colour;
    }
}