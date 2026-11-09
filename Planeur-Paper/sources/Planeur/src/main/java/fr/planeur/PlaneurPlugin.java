package fr.planeur;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Planeur : un objet (gray dye) qui fait planer, 5 utilisations de base,
 * améliorable avec Solidité (Unbreaking) à l'enclume.
 */
public class PlaneurPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // Même réglages que le plugin ModItems
    static final int BASE_USES = 5;
    static final double GLIDE_SPEED = 0.5;
    static final double GLIDE_FALL = 0.2;
    static final String DESC = "§7Clic droit en l'air : plane vers le bas, sans dégâts de chute.";

    private NamespacedKey itemKey;
    private NamespacedKey recipeKey;
    private final Set<UUID> gliding = new HashSet<>();
    private final Map<UUID, Long> glideEnd = new HashMap<>();

    @Override
    public void onEnable() {
        itemKey = new NamespacedKey(this, "planeur");
        recipeKey = new NamespacedKey(this, "planeur_recipe");
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("planeur") != null) {
            getCommand("planeur").setExecutor(this);
            getCommand("planeur").setTabCompleter(this);
        }
        registerRecipe();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.discoverRecipes(List.of(recipeKey));
        }
    }

    // ---------------------------------------------------------------- Item

    private static Component c(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy)
                .decoration(TextDecoration.ITALIC, false);
    }

    private Enchantment unbreaking() {
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
    }

    /** Utilisations max selon le niveau de Solidité : 0 -> 5, I -> 8, II -> 12, III -> 15. */
    private int maxUses(int unbreakingLevel) {
        return BASE_USES + (int) Math.round(unbreakingLevel * 10 / 3.0);
    }

    public boolean isGlider(ItemStack item) {
        if (item == null || item.getType() != Material.GRAY_DYE || !item.hasItemMeta()) return false;
        String id = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return "glider".equals(id);
    }

    public ItemStack createGlider() {
        ItemStack item = new ItemStack(Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(c("§fPlaneur"));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "glider");
        item.setItemMeta(meta);
        syncGlider(item);
        return item;
    }

    /** Met à jour la durabilité max (selon Solidité), la limite de pile et le lore. */
    private void syncGlider(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable d)) return;
        Enchantment unb = unbreaking();
        int max = maxUses(unb == null ? 0 : meta.getEnchantLevel(unb));
        d.setMaxStackSize(1);
        d.setMaxDamage(max);
        d.setDamage(Math.min(d.getDamage(), max - 1));
        d.lore(List.of(c(DESC), c("§eUtilisations restantes : " + (max - d.getDamage()) + "/" + max)));
        item.setItemMeta(d);
    }

    // ---------------------------------------------------------------- Craft

    private void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);
        ShapedRecipe r = new ShapedRecipe(recipeKey, createGlider());
        r.shape("MMM", "FSF", " S ");
        r.setIngredient('M', Material.PHANTOM_MEMBRANE);
        r.setIngredient('F', Material.FEATHER);
        r.setIngredient('S', Material.STICK);
        Bukkit.addRecipe(r);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipes(List.of(recipeKey));
    }

    /** Le planeur est un gray dye : on l'empêche d'être utilisé comme teinture dans un craft. */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack s : e.getInventory().getMatrix()) {
            if (isGlider(s)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- Utilisation

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isGlider(hand)) return;

        if (e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable() && !p.isSneaking()) {
            // laisse ouvrir coffres, portes... mais empêche de teindre les panneaux
            e.setUseItemInHand(Event.Result.DENY);
            return;
        }
        e.setCancelled(true);
        gliderUse(p, hand);
    }

    /** Pas de teinture de moutons, loups, chats... avec le planeur. */
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (isGlider(e.getPlayer().getInventory().getItem(e.getHand()))) e.setCancelled(true);
    }

    @EventHandler
    public void onDispense(BlockDispenseEvent e) {
        if (isGlider(e.getItem())) e.setCancelled(true);
    }

    private boolean airBelow(Player p, int blocks) {
        for (int i = 1; i <= blocks; i++) {
            if (!p.getLocation().subtract(0, i, 0).getBlock().isPassable()) return false;
        }
        return true;
    }

    private void gliderUse(Player p, ItemStack item) {
        UUID id = p.getUniqueId();
        if (gliding.contains(id)) return;
        if (p.isOnGround() || p.isInWater() || p.isGliding() || !airBelow(p, 3)) {
            p.sendActionBar(c("§cUtilise le planeur en l'air, depuis une hauteur."));
            return;
        }

        if (!(item.getItemMeta() instanceof Damageable d)) return;
        Enchantment unb = unbreaking();
        int max = maxUses(unb == null ? 0 : item.getItemMeta().getEnchantLevel(unb));
        int used = d.getDamage() + 1;
        if (p.getGameMode() != GameMode.CREATIVE) {
            if (used >= max) {
                // dernière utilisation : le planeur se casse
                p.getInventory().setItemInMainHand(null);
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            } else {
                d.setMaxDamage(max);
                d.setDamage(used);
                item.setItemMeta(d);
                syncGlider(item);
                p.getInventory().setItemInMainHand(item);
            }
        }
        startGlide(p);
    }

    private void startGlide(Player p) {
        UUID id = p.getUniqueId();
        gliding.add(id);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1.2f, 1.1f);

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                boolean stop = !p.isOnline() || p.isDead() || p.isOnGround() || p.isSneaking()
                        || p.isInWater() || p.isFlying() || p.isGliding() || t > 3600;
                if (stop) {
                    gliding.remove(id);
                    glideEnd.put(id, System.currentTimeMillis());
                    if (p.isOnline()) p.setFallDistance(0f);
                    cancel();
                    return;
                }
                Vector look = p.getLocation().getDirection();
                Vector flat = new Vector(look.getX(), 0, look.getZ());
                if (flat.lengthSquared() > 1.0E-4) {
                    flat.normalize().multiply(GLIDE_SPEED);
                } else {
                    flat = new Vector(0, 0, 0);
                }
                p.setVelocity(new Vector(flat.getX(), -GLIDE_FALL, flat.getZ()));
                p.setFallDistance(0f);
                if (t % 4 == 0) {
                    p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 0.1, 0), 2, 0.3, 0.1, 0.3, 0.01);
                }
                if (t % 14 == 0 && t > 0) {
                    p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.6f, 1.3f);
                }
                t++;
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    /** Pas de dégâts de chute pendant le plané (et 1,5 s après l'atterrissage). */
    @EventHandler
    public void onFallDamage(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(e.getEntity() instanceof Player p)) return;
        UUID id = p.getUniqueId();
        Long end = glideEnd.get(id);
        if (gliding.contains(id) || (end != null && System.currentTimeMillis() - end < 1500)) {
            e.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- Enclume : Solidité

    /**
     * Le gray dye n'accepte pas Solidité en vanilla : on gère l'enclume nous-mêmes.
     * Seul Solidité est autorisé (livre enchanté -> planeur). Pas de réparation, pas de fusion.
     */
    @EventHandler
    public void onAnvil(PrepareAnvilEvent e) {
        AnvilInventory inv = e.getInventory();
        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);
        boolean leftG = isGlider(left);
        boolean rightG = isGlider(right);
        if (!leftG && !rightG) return;

        if (rightG) { // pas de fusion / réparation avec un deuxième planeur
            e.setResult(null);
            return;
        }

        if (right == null || right.getType().isAir()) { // simple renommage
            ItemStack res = e.getResult();
            if (res != null) {
                syncGlider(res);
                e.setResult(res);
            }
            return;
        }

        Enchantment unb = unbreaking();
        if (unb == null
                || right.getType() != Material.ENCHANTED_BOOK
                || !(right.getItemMeta() instanceof EnchantmentStorageMeta book)
                || !book.hasStoredEnchant(unb)) {
            e.setResult(null);
            return;
        }

        ItemStack res = left.clone();
        ItemMeta meta = res.getItemMeta();
        int cur = meta.getEnchantLevel(unb);
        int add = book.getStoredEnchantLevel(unb);
        int lvl = (cur == add) ? cur + 1 : Math.max(cur, add);
        lvl = Math.min(lvl, unb.getMaxLevel());
        if (lvl <= cur) { // rien à améliorer
            e.setResult(null);
            return;
        }
        meta.addEnchant(unb, lvl, true);
        res.setItemMeta(meta);
        syncGlider(res);
        e.setResult(res);
        e.getView().setRepairCost(lvl);
    }

    // ---------------------------------------------------------------- Empêcher l'usage comme teinture (métier à tisser...) / meule

    private boolean blockedTable(InventoryType t) {
        return t == InventoryType.LOOM || t == InventoryType.GRINDSTONE;
    }

    @EventHandler
    public void onTableClick(InventoryClickEvent e) {
        if (!blockedTable(e.getView().getTopInventory().getType())) return;
        boolean bad = isGlider(e.getCursor());
        if (e.isShiftClick() && isGlider(e.getCurrentItem())) bad = true;
        if (e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0
                && isGlider(e.getWhoClicked().getInventory().getItem(e.getHotbarButton()))) bad = true;
        if (e.getClick() == ClickType.SWAP_OFFHAND
                && isGlider(e.getWhoClicked().getInventory().getItemInOffHand())) bad = true;
        if (bad) e.setCancelled(true);
    }

    @EventHandler
    public void onTableDrag(InventoryDragEvent e) {
        if (!blockedTable(e.getView().getTopInventory().getType())) return;
        if (!isGlider(e.getOldCursor())) return;
        int top = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < top) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- Commande

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("planeur.admin")) {
            sender.sendMessage("§cCette commande est réservée aux opérateurs.");
            return true;
        }
        if (args.length < 1 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage("§c/planeur give [joueur]");
            return true;
        }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§cJoueur introuvable : " + args[1]);
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("§c/planeur give <joueur>");
            return true;
        }
        Map<Integer, ItemStack> left = target.getInventory().addItem(createGlider());
        for (ItemStack rest : left.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), rest);
        }
        target.playSound(target.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
        target.sendMessage("§aTu as reçu un §fPlaneur§a !");
        if (target != sender) sender.sendMessage("§aPlaneur donné à " + target.getName() + ".");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("planeur.admin")) return out;
        if (args.length == 1) {
            if ("give".startsWith(args[0].toLowerCase())) out.add("give");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }
}
