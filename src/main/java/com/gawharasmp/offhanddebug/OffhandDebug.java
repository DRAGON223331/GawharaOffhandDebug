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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
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
 * Finds the plugin that cancels offhand actions (F key, offhand slot clicks, offhand drags).
 *
 * How: we register one listener per priority. When the event is not cancelled at our listener of priority N-1
 * but is cancelled at our listener of priority N, the canceller is a listener that sits BETWEEN our two listeners
 * in the event's execution order. We read that exact window from the HandlerList, so the suspect list is usually
 * one or two plugins instead of a whole priority tier.
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
            Bukkit.getPluginManager().registerEvent(InventoryDragEvent.class, this, current,
                    (listener, event) -> {
                        if (event instanceof InventoryDragEvent drag && isOffhandDrag(drag)) {
                            track(event, current, "Offhand inventory drag",
                                    drag.getWhoClicked() instanceof Player p ? p : null);
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

    private boolean isOffhandDrag(InventoryDragEvent drag) {
        // In the player's own inventory view the offhand is raw slot 45.
        return drag.getView().getTopInventory().getType() == InventoryType.CRAFTING && drag.getRawSlots().contains(45);
    }

    private void track(Event event, EventPriority current, String label, Player player) {
        if (!(event instanceof Cancellable cancellable)) return;
        Player actor = player;
        if (actor == null && event instanceof PlayerSwapHandItemsEvent swap) actor = swap.getPlayer();

        boolean cancelled = cancellable.isCancelled();
        Boolean before = lastState.get(event);
        if (cancelled && (before == null || !before)) {
            int index = indexOf(current);
            EventPriority previous = index == 0 ? null : ORDER[index - 1];
            List<String> suspects = suspectsBetween(event, previous, current);
            String window = previous == null ? "before the first " + current + " listener" : "between " + previous + " and " + current;
            String text = label + " was CANCELLED " + window + ". Suspects (in execution order): "
                    + (suspects.isEmpty()
                    ? "none - no plugin listener runs in that window, so it was cancelled by Paper/vanilla or by a packet-level plugin (ProtocolLib/PacketEvents) before the event"
                    : String.join(", ", suspects));
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

    /** Plugins whose listeners run strictly between our listener at {@code previous} and our listener at {@code current}. */
    private List<String> suspectsBetween(Event event, EventPriority previous, EventPriority current) {
        RegisteredListener[] all = event.getHandlers().getRegisteredListeners();
        int from = -1;
        int to = all.length;
        for (int i = 0; i < all.length; i++) {
            if (all[i].getPlugin() != this) continue;
            if (previous != null && all[i].getPriority() == previous) from = i;
            if (all[i].getPriority() == current) to = i;
        }
        Set<String> names = new LinkedHashSet<>();
        for (int i = from + 1; i < to && i < all.length; i++) {
            if (all[i].getPlugin() != this) names.add(all[i].getPlugin().getName() + " (" + all[i].getPriority() + ")");
        }
        return new ArrayList<>(names);
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
