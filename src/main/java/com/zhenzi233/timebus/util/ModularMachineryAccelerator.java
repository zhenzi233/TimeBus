package com.zhenzi233.timebus.util;

import com.zhenzi233.timebus.TimeBus;
import net.minecraft.tileentity.TileEntity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * Modular Machinery (CE) recipe-duration accelerator.
 *
 * <p>MM machine controllers extend {@code TileEntityRestrictedTick} whose
 * {@code update()} is final and deduplicated per world tick, so the Time Bus's
 * normal ITickable update-call path cannot speed them up. Instead we compress
 * the recipe duration inside MM's own modifier system: a duration modifier
 * (multiply by 1/N) is injected into each recipe thread's permanent modifiers.
 *
 * <p>The injected modifier is re-applied automatically to every new recipe
 * context ({@code RecipeThread.createContext} copies permanent/semi-permanent
 * modifiers), and {@code ActiveMachineRecipe.tick()} recomputes totalTick from
 * the context modifiers every tick, so the effect applies immediately and
 * survives recipe switches. Idempotency is guaranteed by
 * {@code hasPermanentModifier} plus a WeakHashMap that remembers the injected
 * multiplier per thread (so a config change re-applies the new value).
 *
 * <p>All access goes through reflection (MM is an optional mod; Time Bus has
 * no hard dependency). If the classes are absent or signatures change, the
 * calls fail softly and are logged.
 */
public final class ModularMachineryAccelerator {

    private ModularMachineryAccelerator() {
    }

    /** Modifier key used for the duration compression (namespaced to avoid collisions). */
    private static final String MODIFIER_KEY = "timebus_duration_accel";

    /** Remembers the multiplier injected per recipe thread, so a config change re-applies. */
    private static final WeakHashMap<Object, Integer> INJECTED = new WeakHashMap<>();

    private static volatile boolean resolved;
    private static volatile boolean available;

    private static volatile Class<?> controllerClass;
    private static volatile Method getRecipeThreadList;
    private static volatile Method hasPermanentModifier;
    private static volatile Method addPermanentModifier;
    private static volatile Method removePermanentModifier;
    private static volatile Constructor<?> recipeModifierCtor;
    private static volatile Object ioInput;
    private static volatile int operationMultiply;

    /** True if the tile is an MM (CE) multiblock machine controller. */
    public static boolean isController(final TileEntity te) {
        if (te == null) {
            return false;
        }
        resolve();
        return available && controllerClass.isInstance(te);
    }

    /**
     * Compress the recipe duration of every recipe thread on the controller by
     * {@code accelerate}. Idempotent: each thread is touched at most once per
     * multiplier; if the multiplier changes, the old modifier is replaced.
     *
     * @return true if at least one thread was touched
     */
    public static boolean apply(final TileEntity te, final int accelerate) {
        if (te == null || accelerate <= 1) {
            return false;
        }
        resolve();
        if (!available) {
            return false;
        }
        boolean touched = false;
        try {
            final Object[] threads = (Object[]) getRecipeThreadList.invoke(te);
            if (threads == null) {
                return false;
            }
            for (final Object thread : threads) {
                if (thread == null) {
                    continue;
                }
                final Integer current = currentInjected(thread);
                if (current != null && current.intValue() == accelerate
                        && (Boolean) hasPermanentModifier.invoke(thread, MODIFIER_KEY)) {
                    continue; // already injected with this multiplier
                }
                removePermanentModifier.invoke(thread, MODIFIER_KEY);
                final Object modifier = recipeModifierCtor.newInstance(null, ioInput,
                        1.0f / accelerate, operationMultiply, false);
                addPermanentModifier.invoke(thread, MODIFIER_KEY, modifier);
                rememberInjected(thread, accelerate);
                touched = true;
            }
            return touched;
        } catch (Exception e) {
            TimeBus.LOGGER.warn("Time Bus: MM acceleration failed at {}: {}", te.getPos(), e.toString());
            return false;
        }
    }

    /** Remove the injected modifier again (immediate restore of the original duration). */
    public static void restore(final TileEntity te) {
        if (te == null) {
            return;
        }
        resolve();
        if (!available) {
            return;
        }
        try {
            final Object[] threads = (Object[]) getRecipeThreadList.invoke(te);
            if (threads == null) {
                return;
            }
            for (final Object thread : threads) {
                if (thread != null) {
                    removePermanentModifier.invoke(thread, MODIFIER_KEY);
                    forgetInjected(thread);
                }
            }
        } catch (Exception e) {
            TimeBus.LOGGER.warn("Time Bus: MM restore failed at {}: {}", te.getPos(), e.toString());
        }
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        synchronized (ModularMachineryAccelerator.class) {
            if (resolved) {
                return;
            }
            try {
                controllerClass = Class.forName(
                        "hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController");
                final Class<?> recipeThreadClass = Class.forName(
                        "hellfirepvp.modularmachinery.common.machine.RecipeThread");
                final Class<?> modifierClass = Class.forName(
                        "hellfirepvp.modularmachinery.common.modifier.RecipeModifier");
                final Class<?> requirementTypeClass = Class.forName(
                        "hellfirepvp.modularmachinery.common.crafting.requirement.type.RequirementType");
                final Class<?> ioTypeClass = Class.forName(
                        "hellfirepvp.modularmachinery.common.machine.IOType");

                // getRecipeThreadList is declared on the concrete controller classes
                // (TileMachineController / TileFactoryController), not on the base.
                getRecipeThreadList = Class.forName(
                        "hellfirepvp.modularmachinery.common.tiles.TileMachineController")
                        .getMethod("getRecipeThreadList");
                hasPermanentModifier = recipeThreadClass.getMethod("hasPermanentModifier", String.class);
                addPermanentModifier = recipeThreadClass.getMethod(
                        "addPermanentModifier", String.class, modifierClass);
                removePermanentModifier = recipeThreadClass.getMethod("removePermanentModifier", String.class);
                recipeModifierCtor = modifierClass.getConstructor(
                        requirementTypeClass, ioTypeClass, float.class, int.class, boolean.class);

                final Field ioInputField = ioTypeClass.getField("INPUT");
                ioInput = ioInputField.get(null);
                operationMultiply = modifierClass.getField("OPERATION_MULTIPLY").getInt(null);
                available = true;
            } catch (Exception e) {
                TimeBus.LOGGER.warn("Time Bus: MM acceleration unavailable: {}", e.toString());
                available = false;
            } finally {
                resolved = true;
            }
        }
    }

    private static Integer currentInjected(final Object thread) {
        synchronized (INJECTED) {
            return INJECTED.get(thread);
        }
    }

    private static void rememberInjected(final Object thread, final int accelerate) {
        synchronized (INJECTED) {
            INJECTED.put(thread, accelerate);
        }
    }

    private static void forgetInjected(final Object thread) {
        synchronized (INJECTED) {
            INJECTED.remove(thread);
        }
    }
}
