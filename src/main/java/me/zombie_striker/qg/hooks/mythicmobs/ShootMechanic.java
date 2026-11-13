package me.zombie_striker.qg.hooks.mythicmobs;

import me.zombie_striker.qg.api.QualityArmory;
import me.zombie_striker.qg.guns.Gun;
import me.zombie_striker.qg.guns.utils.EntityGunUtil;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** MythicMobs shoot mechanic logic, kept free of MythicMobs API types. */
public class ShootMechanic {

    private final String gunName;
    private final double sway;

    public ShootMechanic(Object config) {
        this.gunName = (String) invoke(config, "getString", new String[]{"gun", "g", "weapon"}, null);
        this.sway = ((Number) invoke(config, "getDouble", "sway", 1.0)).doubleValue();
    }

    public String castAtEntity(Object data, Object target) {
        Entity entity = (Entity) invoke(target, "getBukkitEntity");
        Location location;
        if (entity instanceof LivingEntity) {
            location = entity.getLocation().add(0, ((LivingEntity) entity).getEyeHeight() * 0.75, 0);
        } else {
            location = entity.getLocation();
        }
        return shoot(data, location);
    }

    public String castAtLocation(Object data, Object target) {
        try {
            ClassLoader loader = target.getClass().getClassLoader();
            Class<?> adapter = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter", true, loader);
            Object location = invokeStatic(adapter, "adapt", target);
            return shoot(data, (Location) location);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("MythicMobs Bukkit adapter is unavailable", e);
        }
    }

    private String shoot(Object data, Location target) {
        Object caster = invoke(invoke(invoke(data, "getCaster"), "getEntity"), "getBukkitEntity");
        if (!(caster instanceof LivingEntity))
            return "INVALID_TARGET";
        LivingEntity shooter = (LivingEntity) caster;

        Gun gun = getGun(shooter);
        if (gun == null)
            return "INVALID_CONFIG";
        if (target.getWorld() != shooter.getWorld())
            return "INVALID_TARGET";

        EntityGunUtil.shoot(gun, shooter, target.toVector().subtract(shooter.getEyeLocation().toVector()), sway);
        return "SUCCESS";
    }

    private Gun getGun(LivingEntity shooter) {
        if (gunName != null)
            return QualityArmory.getGunByName(gunName);

        EntityEquipment equipment = shooter.getEquipment();
        if (equipment == null)
            return null;
        return QualityArmory.getGun(equipment.getItemInMainHand());
    }

    static Object invoke(Object target, String name, Object... arguments) {
        return call(target.getClass(), target, name, arguments);
    }

    private static Object invokeStatic(Class<?> type, String name, Object... arguments) {
        return call(type, null, name, arguments);
    }

    private static Object call(Class<?> type, Object target, String name, Object... arguments) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != arguments.length)
                continue;
            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (arguments[i] != null && !wrap(parameterTypes[i]).isInstance(arguments[i])) {
                    matches = false;
                    break;
                }
            }
            if (!matches)
                continue;
            try {
                return method.invoke(target, arguments);
            } catch (IllegalAccessException | InvocationTargetException e) {
                Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                throw new IllegalStateException("Unable to call " + type.getName() + "." + name, cause);
            }
        }
        throw new IllegalStateException("Method " + type.getName() + "." + name + " was not found");
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
