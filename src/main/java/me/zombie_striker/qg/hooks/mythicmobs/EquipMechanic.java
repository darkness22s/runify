package me.zombie_striker.qg.hooks.mythicmobs;

import me.zombie_striker.customitemmanager.CustomBaseObject;
import me.zombie_striker.qg.api.QualityArmory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

/** MythicMobs equip mechanic logic, kept free of MythicMobs API types. */
public class EquipMechanic {

    private final String itemName;
    private final String slot;
    private final float dropChance;

    public EquipMechanic(Object config) {
        this.itemName = (String) ShootMechanic.invoke(config, "getString", new String[]{"item", "i", "gun", "g"}, null);
        this.slot = ((String) ShootMechanic.invoke(config, "getString", new String[]{"slot", "s"}, "HAND")).toUpperCase();
        this.dropChance = ((Number) ShootMechanic.invoke(config, "getDouble", "dropchance", 0)).floatValue();
    }

    public String cast(Object data) {
        Object caster = ShootMechanic.invoke(ShootMechanic.invoke(ShootMechanic.invoke(data, "getCaster"), "getEntity"), "getBukkitEntity");
        if (!(caster instanceof LivingEntity))
            return "INVALID_TARGET";
        EntityEquipment equipment = ((LivingEntity) caster).getEquipment();
        if (equipment == null)
            return "INVALID_TARGET";

        CustomBaseObject object = itemName == null ? null : QualityArmory.getCustomItemByName(itemName);
        if (object == null)
            return "INVALID_CONFIG";
        ItemStack item = QualityArmory.getCustomItemAsItemStack(object);

        switch (slot) {
            case "OFFHAND":
            case "OFF_HAND":
                equipment.setItemInOffHand(item);
                equipment.setItemInOffHandDropChance(dropChance);
                break;
            case "HEAD":
            case "HELMET":
                equipment.setHelmet(item);
                equipment.setHelmetDropChance(dropChance);
                break;
            default:
                equipment.setItemInMainHand(item);
                equipment.setItemInMainHandDropChance(dropChance);
                break;
        }
        return "SUCCESS";
    }
}
