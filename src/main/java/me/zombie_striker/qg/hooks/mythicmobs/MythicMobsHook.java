package me.zombie_striker.qg.hooks.mythicmobs;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventExecutor;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.logging.Level;

/**
 * Registers QualityArmory's MythicMobs mechanics without a compile-time
 * dependency on MythicMobs. Its API is loaded only when MythicMobs is present.
 */
public final class MythicMobsHook implements Listener {

    private static final String MECHANIC_LOAD_EVENT = "io.lumine.mythic.bukkit.events.MythicMechanicLoadEvent";
    private static final String NO_TARGET_SKILL = "io.lumine.mythic.api.skills.INoTargetSkill";
    private static final String ENTITY_SKILL = "io.lumine.mythic.api.skills.ITargetedEntitySkill";
    private static final String LOCATION_SKILL = "io.lumine.mythic.api.skills.ITargetedLocationSkill";

    private final Plugin plugin;
    private final ClassLoader mythicClassLoader;

    private MythicMobsHook(Plugin plugin, ClassLoader mythicClassLoader) {
        this.plugin = plugin;
        this.mythicClassLoader = mythicClassLoader;
    }

    @SuppressWarnings("unchecked")
    public static void register(Plugin plugin) throws ClassNotFoundException {
        Plugin mythicMobs = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (mythicMobs == null)
            return;

        ClassLoader classLoader = mythicMobs.getClass().getClassLoader();
        Class<?> eventClass = Class.forName(MECHANIC_LOAD_EVENT, true, classLoader);
        if (!Event.class.isAssignableFrom(eventClass))
            throw new IllegalStateException(MECHANIC_LOAD_EVENT + " is not a Bukkit event");

        MythicMobsHook listener = new MythicMobsHook(plugin, classLoader);
        EventExecutor executor = (registeredListener, event) -> listener.onMechanicLoad(event);
        Bukkit.getPluginManager().registerEvent((Class<? extends Event>) eventClass, listener,
                EventPriority.NORMAL, executor, plugin, true);
    }

    private void onMechanicLoad(Event event) {
        try {
            String name = (String) ShootMechanic.invoke(event, "getMechanicName");
            Object config = ShootMechanic.invoke(event, "getConfig");
            Object mechanic;
            if (name.equalsIgnoreCase("qashoot") || name.equalsIgnoreCase("qualityarmoryshoot")) {
                mechanic = createShootMechanic(config);
            } else if (name.equalsIgnoreCase("qaequip") || name.equalsIgnoreCase("qualityarmoryequip")) {
                mechanic = createEquipMechanic(config);
            } else {
                return;
            }
            ShootMechanic.invoke(event, "register", mechanic);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to register a QualityArmory MythicMobs mechanic", e);
        }
    }

    private Object createShootMechanic(Object config) throws ClassNotFoundException {
        ShootMechanic mechanic = new ShootMechanic(config);
        return createSkillProxy(new String[]{ENTITY_SKILL, LOCATION_SKILL}, mechanic);
    }

    private Object createEquipMechanic(Object config) throws ClassNotFoundException {
        EquipMechanic mechanic = new EquipMechanic(config);
        return createSkillProxy(new String[]{NO_TARGET_SKILL}, mechanic);
    }

    private Object createSkillProxy(String[] skillInterfaces, Object mechanic) throws ClassNotFoundException {
        Class<?>[] interfaces = new Class<?>[skillInterfaces.length];
        for (int i = 0; i < skillInterfaces.length; i++)
            interfaces[i] = Class.forName(skillInterfaces[i], true, mythicClassLoader);

        return Proxy.newProxyInstance(mythicClassLoader, interfaces, new SkillInvocationHandler(mechanic));
    }

    private static final class SkillInvocationHandler implements InvocationHandler {
        private final Object mechanic;

        private SkillInvocationHandler(Object mechanic) {
            this.mechanic = mechanic;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                if ("toString".equals(name)) return "QualityArmoryMythicMechanic";
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) return proxy == arguments[0];
            }
            if ("getThreadSafetyLevel".equals(name))
                return enumValue(method.getReturnType(), "SYNC_ONLY");
            if (method.isDefault())
                return InvocationHandler.invokeDefault(proxy, method, arguments == null ? new Object[0] : arguments);

            String result;
            if (mechanic instanceof ShootMechanic) {
                ShootMechanic shoot = (ShootMechanic) mechanic;
                if ("castAtEntity".equals(name))
                    result = shoot.castAtEntity(arguments[0], arguments[1]);
                else if ("castAtLocation".equals(name))
                    result = shoot.castAtLocation(arguments[0], arguments[1]);
                else
                    return null;
            } else {
                if (!"cast".equals(name)) return null;
                result = ((EquipMechanic) mechanic).cast(arguments[0]);
            }
            return enumValue(method.getReturnType(), result);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Object enumValue(Class<?> type, String name) {
            return Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), name);
        }
    }
}
