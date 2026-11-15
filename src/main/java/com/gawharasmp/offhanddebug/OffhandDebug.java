package com.gawharasmp.offhanddebug;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the plugin that cancels offhand actions.
 *
 * Idea: we listen at EVERY priority. If the event is not cancelled at priority N but is cancelled
 * at priority N+1, then the plugin that cancelled it is one of the plugins listening at priority N.
 */
public final class OffhandDebug extends JavaPlugin implements Listener {
    private static final EventPriority[] ORDER = {
            EventPriority.LOWEST, EventPriority.LOW, EventPriority.NORMAL,
            EventPriority.HIGH, EventPriority.HIGHEST, EventPriority.MONITOR
    };

    /** Last seen cancelled state per event instance. */
    private final Map<Event, Boolean> lastState = new IdentityHashMap<>();

    @Override
    public void onEnable() {
        for (EventPriority priority : ORDER) {
            final EventPriority current = priority;
            Bukkit.getPluginManager().registerEvent(PlayerSwapHandItemsEvent.class, this, current,
                    (listener, event) -> track(event, current, "Offhand swap (F key)", null), this, false);
            Bukkit.getPluginManager().registerEvent(InventoryClickEvent.class, this, current,
                    (listener, event) -> {
                        if (event instanceof InventoryClickEvent click && isOffhandClick(click)) {
                            track(event, current, "Offhand inventory click (" + click.getClick() + ", slot " + click.getSlot() + ")",
                                    click.getWhoClicked() instanceof Player p ? p : null);
                        }
                    }, this, false);
        }
        // Wait one tick so every other plugin has registered its listeners.
        Bukkit.getScheduler().runTask(this, () -> {
            getLogger().info("Plugins listening to the F-key swap event:");
            for (String line : describe(PlayerSwapHandItemsEvent.getHandlerList())) getLogger().info("  " + line);
            getLogger().info("Plugins listening to inventory clicks:");
            for (String line : describe(InventoryClickEvent.getHandlerList())) getLogger().info("  " + line);
        });
    }

    private boolean isOffhandClick(InventoryClickEvent click) {
        if (click.getClick() == ClickType.SWAP_OFFHAND) return true;
        return click.getClickedInventory() instanceof PlayerInventory && click.getSlot() == 40;
    }

    private void track(Event event, EventPriority current, String label, Player player) {
        if (!(event instanceof Cancellable cancellable)) return;
        Player actor = player;
        if (actor == null && event instanceof PlayerSwapHandItemsEvent swap) actor = swap.getPlayer();

        boolean cancelled = cancellable.isCancelled();
        Boolean before = lastState.get(event);
        if (cancelled && (before == null || !before)) {
            int index = indexOf(current);
            EventPriority suspectPriority = index == 0 ? current : ORDER[index - 1];
            Set<String> suspects = pluginsAt(event, suspectPriority);
            String text = label + " was CANCELLED. Cancelled at/after " + suspectPriority
                    + " by one of: " + (suspects.isEmpty() ? "(unknown / another listener)" : String.join(", ", suspects));
            getLogger().warning((actor != null ? actor.getName() + ": " : "") + text);
            if (actor != null && actor.hasPermission("gawharaoffhanddebug.use")) actor.sendMessage("§e[OffhandDebug] §c" + text);
        }
        lastState.put(event, cancelled);
        if (current == EventPriority.MONITOR) lastState.remove(event);
    }

    private int indexOf(EventPriority priority) {
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i] == priority) return i;
        return 0;
    }

    private Set<String> pluginsAt(Event event, EventPriority priority) {
        Set<String> names = new LinkedHashSet<>();
        for (RegisteredListener listener : event.getHandlers().getRegisteredListeners()) {
            if (listener.getPriority() == priority && listener.getPlugin() != this) names.add(listener.getPlugin().getName());
        }
        return names;
    }

    private List<String> describe(HandlerList list) {
        List<String> lines = new ArrayList<>();
        for (EventPriority priority : ORDER) {
            Set<String> names = new LinkedHashSet<>();
            for (RegisteredListener listener : list.getRegisteredListeners()) {
                if (listener.getPriority() == priority && listener.getPlugin() != this) names.add(listener.getPlugin().getName());
            }
            if (!names.isEmpty()) lines.add(priority + ": " + String.join(", ", names));
        }
        if (lines.isEmpty()) lines.add("(no other plugin listens)");
        return lines;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("gawharaoffhanddebug.use")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        sender.sendMessage("§e[OffhandDebug] §fF-key swap listeners:");
        for (String line : describe(PlayerSwapHandItemsEvent.getHandlerList())) sender.sendMessage("§7  " + line);
        sender.sendMessage("§e[OffhandDebug] §fInventory click listeners:");
        for (String line : describe(InventoryClickEvent.getHandlerList())) sender.sendMessage("§7  " + line);
        sender.sendMessage("§aNow press F or put a shield in your offhand slot; if a plugin cancels it you will see its name here.");
        return true;
    }
}
