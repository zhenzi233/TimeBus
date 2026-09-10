package com.zhenzi233.timebus.util;

import com.zhenzi233.timebus.TimeBus;
import net.minecraft.tileentity.TileEntity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modular Machinery (CE) 反射解析：类名 / 方法 / 字段句柄的惰性解析与缓存。
 *
 * <p>从原 {@code ModularMachineryAccelerator} 里搬出。解析结果发布为一个不可变
 * 快照 {@link Handles}：先在本线程内把所有句柄填完，再一次性写入
 * {@code volatile handles}，因此不会出现"只填了一半"的可见状态。
 *
 * <p>MM 是可选依赖（Time Bus 不硬依赖它）：类缺失或签名变化时
 * {@link #handles()} 返回 {@code null} 并记一条 warn，之后不再重试。
 */
final class MMReflection {

    private MMReflection() {
    }

    /**
     * 解析出的 MM 类型、方法与字段句柄。全部字段为 final，构造完成后一次性发布；
     * 解析失败时整体保持 {@code null}。
     */
    static final class Handles {
        final Class<?> controllerClass;
        final Method getPermanentModifiers;
        final Method getModifier;
        final Method addPermanentModifier;
        final Method removePermanentModifier;
        final Method getSemiPermanentModifiers;
        final Method addModifier;
        final Method removeModifier;
        final Method getActiveRecipe;
        final Constructor<?> recipeModifierCtor;
        final Object ioInput;
        final Object ioOutput;
        final int operationMultiply;
        /**
         * 配方时长 modifier 的 target（RequirementTypesMM.REQUIREMENT_DURATION）。
         * MM 计算 totalTick 时按该 target 查找 modifier（ActiveMachineRecipe.tick），
         * 传 null 或其它类型都不会命中；同时该 target 已注册，序列化/同步时才
         * 不会产生空注册名导致客户端反序列化崩溃。
         */
        final Object recipeDurationType;
        /**
         * 能耗 modifier 的 target（RequirementTypesMM.REQUIREMENT_ENERGY）。
         * MM 的 RequirementEnergy.deepCopyModified() 按该 target + IOType 匹配
         * modifier：input 放大机器每 tick 消耗，output 放大机器每 tick 产出，
         * 使加速后单次配方总耗电/总产出守恒。
         */
        final Object recipeEnergyType;

        private Handles(final Class<?> controllerClass, final Method getPermanentModifiers,
                        final Method getModifier, final Method addPermanentModifier,
                        final Method removePermanentModifier, final Method getSemiPermanentModifiers,
                        final Method addModifier, final Method removeModifier, final Method getActiveRecipe,
                        final Constructor<?> recipeModifierCtor, final Object ioInput, final Object ioOutput,
                        final int operationMultiply, final Object recipeDurationType,
                        final Object recipeEnergyType) {
            this.controllerClass = controllerClass;
            this.getPermanentModifiers = getPermanentModifiers;
            this.getModifier = getModifier;
            this.addPermanentModifier = addPermanentModifier;
            this.removePermanentModifier = removePermanentModifier;
            this.getSemiPermanentModifiers = getSemiPermanentModifiers;
            this.addModifier = addModifier;
            this.removeModifier = removeModifier;
            this.getActiveRecipe = getActiveRecipe;
            this.recipeModifierCtor = recipeModifierCtor;
            this.ioInput = ioInput;
            this.ioOutput = ioOutput;
            this.operationMultiply = operationMultiply;
            this.recipeDurationType = recipeDurationType;
            this.recipeEnergyType = recipeEnergyType;
        }
    }

    /** 已发布的句柄快照；null 表示解析尚未成功（含解析失败，失败后不再重试）。 */
    private static volatile Handles handles;

    /**
     * getRecipeThreadList is declared separately on the concrete controller
     * classes (TileMachineController and TileFactoryController), not on the
     * shared base, so it must be resolved from the actual tile class at
     * runtime and cached per class.
     */
    private static final Map<Class<?>, Method> GET_RECIPE_THREAD_LIST = new ConcurrentHashMap<>();

    /**
     * 返回 MM 反射句柄；首次调用解析一次。MM 不存在或签名不符时返回
     * {@code null}（并记一条 warn），之后不再重试。
     */
    static Handles handles() {
        Handles current = handles;
        if (current != null) {
            return current;
        }
        synchronized (MMReflection.class) {
            if (handles != null) {
                return handles;
            }
            try {
                handles = resolveHandles();
            } catch (Exception e) {
                TimeBus.LOGGER.warn("Time Bus: MM acceleration unavailable: {}", e.toString());
                handles = null;
            }
        }
        return handles;
    }

    private static Handles resolveHandles() throws Exception {
        final Class<?> controllerClass = Class.forName(
                "hellfirepvp.modularmachinery.common.tiles.base.TileMultiblockMachineController");
        final Class<?> recipeThreadClass = Class.forName(
                "hellfirepvp.modularmachinery.common.machine.RecipeThread");
        final Class<?> modifierClass = Class.forName(
                "hellfirepvp.modularmachinery.common.modifier.RecipeModifier");
        final Class<?> requirementTypeClass = Class.forName(
                "hellfirepvp.modularmachinery.common.crafting.requirement.type.RequirementType");
        final Class<?> ioTypeClass = Class.forName(
                "hellfirepvp.modularmachinery.common.machine.IOType");

        final Method getPermanentModifiers = recipeThreadClass.getMethod("getPermanentModifiers");
        final Method getModifier = modifierClass.getMethod("getModifier");
        final Method addPermanentModifier = recipeThreadClass.getMethod(
                "addPermanentModifier", String.class, modifierClass);
        final Method removePermanentModifier = recipeThreadClass.getMethod("removePermanentModifier", String.class);
        final Method getSemiPermanentModifiers = recipeThreadClass.getMethod("getSemiPermanentModifiers");
        final Method addModifier = recipeThreadClass.getMethod("addModifier", String.class, modifierClass);
        final Method removeModifier = recipeThreadClass.getMethod("removeModifier", String.class);
        // 故意提前校验 ActiveMachineRecipe 是否存在（原 resolve() 里同样如此）：
        // 它参与 getActiveRecipe() 的返回语义，缺失即说明 MM 签名不符。
        Class.forName("hellfirepvp.modularmachinery.common.crafting.ActiveMachineRecipe");
        final Method getActiveRecipe = recipeThreadClass.getMethod("getActiveRecipe");
        final Constructor<?> recipeModifierCtor = modifierClass.getConstructor(
                requirementTypeClass, ioTypeClass, float.class, int.class, boolean.class);

        final Field ioInputField = ioTypeClass.getField("INPUT");
        final Object ioInput = ioInputField.get(null);
        final Object ioOutput = ioTypeClass.getField("OUTPUT").get(null);
        final int operationMultiply = modifierClass.getField("OPERATION_MULTIPLY").getInt(null);
        // 配方时长与能耗专用 target；必须取注册过的实例，构造 modifier 时传给
        // 第一个参数，否则序列化出空注册名（见 serialize()/deserialize()）。
        final Class<?> requirementTypesClass = Class.forName(
                "hellfirepvp.modularmachinery.common.lib.RequirementTypesMM");
        final Object recipeDurationType = requirementTypesClass.getField("REQUIREMENT_DURATION").get(null);
        final Object recipeEnergyType = requirementTypesClass.getField("REQUIREMENT_ENERGY").get(null);
        return new Handles(controllerClass, getPermanentModifiers, getModifier, addPermanentModifier,
                removePermanentModifier, getSemiPermanentModifiers, addModifier, removeModifier,
                getActiveRecipe, recipeModifierCtor, ioInput, ioOutput, operationMultiply,
                recipeDurationType, recipeEnergyType);
    }

    /**
     * Resolve getRecipeThreadList from the actual tile class, walking up the
     * hierarchy until a declaration is found (TileMachineController and
     * TileFactoryController each declare their own copy).
     */
    static Method getRecipeThreadListFor(final TileEntity te) {
        if (te == null) {
            return null;
        }
        return GET_RECIPE_THREAD_LIST.computeIfAbsent(te.getClass(), clazz -> {
            Class<?> current = clazz;
            while (current != null) {
                try {
                    return current.getDeclaredMethod("getRecipeThreadList");
                } catch (NoSuchMethodException e) {
                    current = current.getSuperclass();
                }
            }
            return null;
        });
    }
}
