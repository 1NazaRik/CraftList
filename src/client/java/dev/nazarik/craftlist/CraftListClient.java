package dev.nazarik.craftlist;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class CraftListClient implements ClientModInitializer {
	public static final String MOD_ID = "craftlist";
	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final Path SAVE_FILE = FabricLoader.getInstance().getConfigDir().resolve("craftlist.json");
	private static final List<PlanTask> TASKS = new ArrayList<>();
	private static final KeyMapping OPEN_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.craftlist.open",
		75,
		KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "controls"))
	));
	private static boolean hudVisible = true;
	private static boolean screenOpen;
	private static Object currentLevel;

	@Override
	public void onInitializeClient() {
		loadTasks();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (OPEN_KEY.consumeClick()) {
				if (client.player != null) client.setScreenAndShow(new CraftListScreen());
			}
			if (client.level != currentLevel) {
				currentLevel = client.level;
				for (PlanTask task : TASKS) task.display = null;
			}
			resolveTasks(client);
		});
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
			Identifier.fromNamespaceAndPath(MOD_ID, "craft_list"), CraftListClient::renderHud);
	}

	static List<RecipeOption> knownRecipes(Minecraft client) {
		if (client.player == null) return List.of();
		Map<String, RecipeOption> unique = new LinkedHashMap<>();
		for (var collection : client.player.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				Optional<List<Ingredient>> requirements = entry.craftingRequirements();
				if (requirements.isEmpty() || requirements.get().isEmpty()) continue;
				ItemStack output = result(entry);
				if (output.isEmpty()) continue;
				String itemId = itemId(output.getItem());
				String signature = signature(requirements.get());
				unique.putIfAbsent(itemId + "|" + signature, new RecipeOption(entry, output, itemId, signature));
			}
		}
		return unique.values().stream()
			.sorted(Comparator.comparing(option -> option.output().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER))
			.toList();
	}

	static List<PlanTask> tasks() {
		return List.copyOf(TASKS);
	}

	static void addTask(RecipeOption option, int amount) {
		for (PlanTask task : TASKS) {
			if (task.itemId.equals(option.itemId()) && task.signature.equals(option.signature())) {
				task.amount = Math.min(1024, task.amount + amount);
				task.display = option.entry();
				saveTasks();
				return;
			}
		}
		if (TASKS.size() >= 5) return;
		TASKS.add(new PlanTask(option.entry(), option.itemId(), option.signature(), amount));
		saveTasks();
	}

	static void changeAmount(int index, int delta) {
		if (index < 0 || index >= TASKS.size()) return;
		PlanTask task = TASKS.get(index);
		task.amount = Math.max(1, Math.min(1024, task.amount + delta));
		saveTasks();
	}

	static void removeTask(int index) {
		if (index < 0 || index >= TASKS.size()) return;
		TASKS.remove(index);
		saveTasks();
	}

	static boolean hudVisible() {
		return hudVisible;
	}

	static void setScreenOpen(boolean open) { screenOpen = open; }

	static void toggleHud() {
		hudVisible = !hudVisible;
	}

	static ItemStack output(PlanTask task) {
		return task.display == null ? ItemStack.EMPTY : result(task.display);
	}

	static String itemId(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	static List<MaterialNeed> materialNeeds(PlanTask task, Minecraft client) {
		if (task.display == null || task.display.craftingRequirements().isEmpty() || client.player == null) return List.of();
		List<Ingredient> ingredients = task.display.craftingRequirements().get();
		ItemStack output = result(task.display);
		if (output.isEmpty()) return List.of();
		int crafts = (task.amount + output.getCount() - 1) / output.getCount();
		Map<String, MaterialNeed> grouped = new LinkedHashMap<>();
		for (Ingredient ingredient : ingredients) {
			List<Item> options = ingredient.items().map(Holder::value).toList();
			if (options.isEmpty()) continue;
			String key = ingredientSignature(ingredient);
			MaterialNeed need = grouped.computeIfAbsent(key, ignored -> new MaterialNeed(ingredient, options.getFirst(), 0, 0));
			need.required += crafts;
		}
		Inventory inventory = client.player.getInventory();
		for (MaterialNeed need : grouped.values()) {
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
				ItemStack stack = inventory.getItem(slot);
				if (!stack.isEmpty() && need.ingredient.test(stack)) need.have += stack.getCount();
			}
		}
		return List.copyOf(grouped.values());
	}

	private static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
		Minecraft client = Minecraft.getInstance();
		if (!hudVisible || client.player == null || TASKS.isEmpty() || screenOpen) return;
		List<PlanTask> visible = TASKS.stream().filter(task -> !output(task).isEmpty()).limit(4).toList();
		if (visible.isEmpty()) return;

		int x = graphics.guiWidth() - 172;
		int y = 10;
		int lineCount = 1;
		for (PlanTask task : visible) lineCount += 2 + Math.min(3, materialNeeds(task, client).size());
		graphics.fill(x - 5, y - 5, graphics.guiWidth() - 5, y + lineCount * 10 + 3, 0x990B1110);
		graphics.text(client.font, "CraftList", x, y, 0xFFE7E8D8);
		y += 12;
		for (PlanTask task : visible) {
			ItemStack output = output(task);
			graphics.text(client.font, output.getHoverName().getString() + " × " + task.amount, x, y, 0xFFFFFFFF);
			y += 10;
			List<MaterialNeed> needs = materialNeeds(task, client);
			for (MaterialNeed need : needs.stream().limit(3).toList()) {
				String line = new ItemStack(need.item).getHoverName().getString() + " " + Math.min(need.have, need.required) + "/" + need.required;
				graphics.text(client.font, line, x + 7, y, need.have >= need.required ? 0xFF9CD49D : 0xFFE7C77B);
				y += 10;
			}
			if (needs.size() > 3) {
				graphics.text(client.font, "… ещё " + (needs.size() - 3), x + 7, y, 0xFFB7B9AA);
				y += 10;
			}
		}
	}

	private static ItemStack result(RecipeDisplayEntry entry) {
		try {
			return entry.resultItems(ContextMap.EMPTY).stream().filter(stack -> !stack.isEmpty()).findFirst().orElse(ItemStack.EMPTY);
		} catch (RuntimeException ignored) {
			return ItemStack.EMPTY;
		}
	}

	private static String signature(List<Ingredient> ingredients) {
		return ingredients.stream().map(CraftListClient::ingredientSignature).sorted().collect(Collectors.joining(";"));
	}

	private static String ingredientSignature(Ingredient ingredient) {
		return ingredient.items().map(Holder::value).map(CraftListClient::itemId).sorted().collect(Collectors.joining(","));
	}

	private static void resolveTasks(Minecraft client) {
		if (client.player == null || TASKS.stream().allMatch(task -> task.display != null)) return;
		List<RecipeOption> recipes = knownRecipes(client);
		for (PlanTask task : TASKS) {
			if (task.display != null) continue;
			recipes.stream()
				.filter(option -> option.itemId().equals(task.itemId) && option.signature().equals(task.signature))
				.findFirst().ifPresent(option -> task.display = option.entry());
		}
	}

	private static void loadTasks() {
		if (!Files.exists(SAVE_FILE)) return;
		try {
			JsonElement root = JsonParser.parseString(Files.readString(SAVE_FILE));
			if (!root.isJsonArray()) return;
			for (JsonElement element : root.getAsJsonArray()) {
				if (!element.isJsonObject()) continue;
				JsonObject object = element.getAsJsonObject();
				String itemId = object.get("item").getAsString();
				String signature = object.get("ingredients").getAsString();
				int amount = Math.max(1, Math.min(1024, object.get("amount").getAsInt()));
				TASKS.add(new PlanTask(null, itemId, signature, amount));
				if (TASKS.size() == 5) break;
			}
		} catch (IOException | RuntimeException exception) {
			LOGGER.warn("Could not read saved craft list", exception);
		}
	}

	private static void saveTasks() {
		JsonArray array = new JsonArray();
		for (PlanTask task : TASKS) {
			JsonObject object = new JsonObject();
			object.addProperty("item", task.itemId);
			object.addProperty("ingredients", task.signature);
			object.addProperty("amount", task.amount);
			array.add(object);
		}
		try {
			Files.createDirectories(SAVE_FILE.getParent());
			Files.writeString(SAVE_FILE, array.toString());
		} catch (IOException exception) {
			LOGGER.warn("Could not save craft list", exception);
		}
	}

	record RecipeOption(RecipeDisplayEntry entry, ItemStack output, String itemId, String signature) {}

	static final class PlanTask {
		final String itemId;
		final String signature;
		int amount;
		RecipeDisplayEntry display;

		PlanTask(RecipeDisplayEntry display, String itemId, String signature, int amount) {
			this.display = display;
			this.itemId = itemId;
			this.signature = signature;
			this.amount = amount;
		}
	}

	static final class MaterialNeed {
		final Ingredient ingredient;
		final Item item;
		int required;
		int have;

		MaterialNeed(Ingredient ingredient, Item item, int required, int have) {
			this.ingredient = ingredient;
			this.item = item;
			this.required = required;
			this.have = have;
		}
	}
}
