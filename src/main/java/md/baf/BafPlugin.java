package md.baf;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BafPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final String S = "\u00a7";

    private NamespacedKey idKey;      // id баффа в предмете
    private NamespacedKey activeKey;  // до какого времени действует баф у игрока

    private static class Baf {
        String id;
        String name;
        List<String> lore = new ArrayList<>();
        Material material = Material.POTION;
        boolean glow, hideEffects;
        Color color;
        List<PotionEffect> effects = new ArrayList<>();
        long maxTicks;
    }

    private final Map<String, Baf> bafs = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        idKey = new NamespacedKey(this, "baf_id");
        activeKey = new NamespacedKey(this, "baf_active_until");
        loadBafs();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("ei");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    // ------------------------------------------------------------ утилиты

    private static String norm(String id) {
        return id.toLowerCase(Locale.ROOT).replace("\u2019", "").replace("'", "");
    }

    private static String color(String s) {
        Matcher m = HEX.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            StringBuilder r = new StringBuilder(S + "x");
            for (char c : m.group(1).toCharArray()) r.append(S).append(c);
            m.appendReplacement(sb, Matcher.quoteReplacement(r.toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    private String msg(String key, String... repl) {
        String s = getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < repl.length; i += 2) s = s.replace(repl[i], repl[i + 1]);
        return color(getConfig().getString("messages.prefix", "") + s);
    }

    private PotionEffectType effectType(String name) {
        String k = name.toLowerCase(Locale.ROOT);
        switch (k) {
            case "damage_resistance": k = "resistance"; break;
            case "increase_damage": k = "strength"; break;
            case "fast_digging": k = "haste"; break;
            case "slow_digging": k = "mining_fatigue"; break;
            case "jump": k = "jump_boost"; break;
            default: break;
        }
        PotionEffectType t = PotionEffectType.getByKey(NamespacedKey.minecraft(k));
        if (t == null) t = PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
        return t;
    }

    private void loadBafs() {
        bafs.clear();
        ConfigurationSection items = getConfig().getConfigurationSection("items");
        if (items == null) return;
        boolean particles = getConfig().getBoolean("settings.particles", false);
        boolean icon = getConfig().getBoolean("settings.icon", false);

        for (String id : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(id);
            if (s == null) continue;
            Baf b = new Baf();
            b.id = id;
            b.name = s.getString("name", id);
            b.lore = s.getStringList("lore");
            Material mat = Material.matchMaterial(s.getString("material", "POTION"));
            b.material = mat != null ? mat : Material.POTION;
            b.glow = s.getBoolean("glow", false);
            b.hideEffects = s.getBoolean("hide-effects", false);
            String col = s.getString("color");
            if (col != null && !col.isEmpty()) {
                try {
                    b.color = Color.fromRGB(Integer.parseInt(col.replace("#", ""), 16));
                } catch (Exception ex) {
                    getLogger().warning("Неверный color у " + id + ": " + col);
                }
            }
            for (String line : s.getStringList("effects")) {
                try {
                    String[] p = line.split(";");
                    PotionEffectType t = effectType(p[0].trim());
                    if (t == null) {
                        getLogger().warning("Неизвестный эффект '" + p[0] + "' у " + id);
                        continue;
                    }
                    int ticks = Integer.parseInt(p[1].trim());
                    int amp = Integer.parseInt(p[2].trim());
                    b.effects.add(new PotionEffect(t, ticks, amp, false, particles, icon));
                    b.maxTicks = Math.max(b.maxTicks, ticks);
                } catch (Exception ex) {
                    getLogger().warning("Неверная строка эффекта у " + id + ": " + line);
                }
            }
            bafs.put(norm(id), b);
        }
        getLogger().info("Загружено баффов: " + bafs.size());
    }

    private ItemStack createItem(Baf b) {
        ItemStack it = new ItemStack(b.material);
        ItemMeta m = it.getItemMeta();
        if (m == null) return it;

        m.setDisplayName(color("&r" + b.name));
        if (!b.lore.isEmpty()) {
            m.setLore(b.lore.stream().map(l -> color("&r" + l)).collect(Collectors.toList()));
        }
        if (m instanceof PotionMeta) {
            PotionMeta pm = (PotionMeta) m;
            if (b.color != null) pm.setColor(b.color);
            for (PotionEffect e : b.effects) pm.addCustomEffect(e, true);
        }
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (b.hideEffects) {
            for (String n : new String[]{"HIDE_ADDITIONAL_TOOLTIP", "HIDE_POTION_EFFECTS"}) {
                try {
                    m.addItemFlags(ItemFlag.valueOf(n));
                    break;
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (b.glow) {
            Enchantment ench = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
            if (ench != null) {
                m.addEnchant(ench, 1, true);
                m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
        }
        m.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, norm(b.id));
        it.setItemMeta(m);
        return it;
    }

    private Baf bafOf(ItemStack it) {
        if (it == null || it.getType() == Material.AIR || !it.hasItemMeta()) return null;
        String id = it.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return id == null ? null : bafs.get(id);
    }

    // ------------------------------------------------------------ события

    /** Выпил зелье: если уже действует другой баф - запрещаем (баффы не стакаются). */
    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        ItemStack item = e.getItem();

        if (item.getType() == Material.MILK_BUCKET) { // молоко снимает эффекты
            p.getPersistentDataContainer().remove(activeKey);
            return;
        }
        Baf b = bafOf(item);
        if (b == null) return;

        long now = System.currentTimeMillis();
        Long until = p.getPersistentDataContainer().get(activeKey, PersistentDataType.LONG);
        if (until != null && until > now) {
            e.setCancelled(true);
            p.sendMessage(msg("already-active", "%seconds%", String.valueOf((until - now) / 1000 + 1)));
            return;
        }
        p.getPersistentDataContainer().set(activeKey, PersistentDataType.LONG, now + b.maxTicks * 50L);

        // выпиваем вручную: без пустой бутылки
        e.setCancelled(true);
        PlayerInventory inv = p.getInventory();
        if (bafOf(inv.getItemInMainHand()) == b) {
            ItemStack h = inv.getItemInMainHand();
            if (h.getAmount() > 1) h.setAmount(h.getAmount() - 1); else inv.setItemInMainHand(null);
        } else if (bafOf(inv.getItemInOffHand()) == b) {
            ItemStack h = inv.getItemInOffHand();
            if (h.getAmount() > 1) h.setAmount(h.getAmount() - 1); else inv.setItemInOffHand(null);
        }
        for (PotionEffect ef : b.effects) p.addPotionEffect(ef);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1f, 1f);
    }

    /** Смерть: сбрасываем баф, а зелья ВСЕГДА выпадают (даже при keepInventory). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        p.getPersistentDataContainer().remove(activeKey);
        if (!e.getKeepInventory()) return; // обычная смерть - предметы выпадают сами, в том же количестве

        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && bafOf(it) != null) {
                p.getWorld().dropItemNaturally(p.getLocation(), it.clone());
                inv.setItem(i, null);
            }
        }
    }

    /** Нельзя класть баф-зелья в варочную стойку. */
    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!getConfig().getBoolean("settings.block-brewing", true)) return;
        if (e.getView().getTopInventory().getType() != InventoryType.BREWING) return;

        boolean baf = bafOf(e.getCurrentItem()) != null || bafOf(e.getCursor()) != null;
        if (!baf && e.getClick() == ClickType.NUMBER_KEY && e.getWhoClicked() instanceof Player) {
            int slot = e.getHotbarButton();
            if (slot >= 0) baf = bafOf(((Player) e.getWhoClicked()).getInventory().getItem(slot)) != null;
        }
        if (baf) e.setCancelled(true);
    }

    // ------------------------------------------------------------ команда /ei

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("baf.admin")) {
            s.sendMessage(msg("no-permission"));
            return true;
        }
        if (a.length == 0) {
            s.sendMessage(msg("usage"));
            return true;
        }
        if (a[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadBafs();
            s.sendMessage(msg("reloaded", "%count%", String.valueOf(bafs.size())));
            return true;
        }
        if (!a[0].equalsIgnoreCase("give") || a.length < 3) {
            s.sendMessage(msg("usage"));
            return true;
        }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) {
            s.sendMessage(msg("player-not-found"));
            return true;
        }
        Baf b = bafs.get(norm(a[2]));
        if (b == null) {
            String list = bafs.values().stream().map(x -> x.id).collect(Collectors.joining(", "));
            s.sendMessage(msg("item-not-found", "%list%", list));
            return true;
        }
        int amount = 1;
        if (a.length > 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(a[3])));
            } catch (NumberFormatException ignored) {
            }
        }
        for (int i = 0; i < amount; i++) { // зелья не стакаются - каждое отдельным предметом
            Map<Integer, ItemStack> left = t.getInventory().addItem(createItem(b));
            for (ItemStack r : left.values()) t.getWorld().dropItemNaturally(t.getLocation(), r);
        }
        s.sendMessage(msg("given", "%amount%", String.valueOf(amount), "%item%", b.id, "%player%", t.getName()));
        t.sendMessage(msg("received"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("baf.admin")) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(Arrays.asList("give", "reload"));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("give")) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (a.length == 3 && a[0].equalsIgnoreCase("give")) {
            bafs.values().forEach(b -> out.add(b.id));
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(last)).collect(Collectors.toList());
    }
}
