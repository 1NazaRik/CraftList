package dev.nazarik.craftlist;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

final class CraftListScreen extends Screen {
	private static final int PANEL_WIDTH = 430;
	private final String query;
	private List<CraftListClient.RecipeOption> results = List.of();
	private EditBox search;

	CraftListScreen() { this(""); }
	private CraftListScreen(String query) {
		super(Component.literal("CraftList"));
		this.query = query;
	}

	@Override protected void init() {
		CraftListClient.setScreenOpen(true);
		int left = (width - PANEL_WIDTH) / 2;
		search = addRenderableWidget(new EditBox(font, left + 12, 40, 275, 20, Component.literal("Поиск рецепта")));
		search.setMaxLength(64);
		search.setValue(query);
		addRenderableWidget(Button.builder(Component.literal("Найти"), button ->
			Minecraft.getInstance().setScreenAndShow(new CraftListScreen(search.getValue()))
		).bounds(left + 294, 40, 124, 20).build());

		results = CraftListClient.knownRecipes(Minecraft.getInstance()).stream()
			.filter(option -> query.isBlank() || option.output().getHoverName().getString().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)) || option.itemId().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
			.limit(4).toList();
		for (int i = 0; i < results.size(); i++) {
			CraftListClient.RecipeOption option = results.get(i);
			String label = option.output().getHoverName().getString() + "  + в список";
			int y = 112 + i * 25;
			addRenderableWidget(Button.builder(Component.literal(label), button -> {
				CraftListClient.addTask(option, 1);
				Minecraft.getInstance().setScreenAndShow(new CraftListScreen(search.getValue()));
			}).bounds(left + 12, y, PANEL_WIDTH - 24, 21).build());
		}

		int rowY = 214;
		List<CraftListClient.PlanTask> tasks = CraftListClient.tasks();
		for (int i = 0; i < tasks.size(); i++) {
			final int index = i;
			int y = rowY + i * 25;
			addRenderableWidget(Button.builder(Component.literal("−"), b -> refresh(() -> CraftListClient.changeAmount(index, -1))).bounds(left + 292, y, 30, 20).build());
			addRenderableWidget(Button.builder(Component.literal("+"), b -> refresh(() -> CraftListClient.changeAmount(index, 1))).bounds(left + 326, y, 30, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Убрать"), b -> refresh(() -> CraftListClient.removeTask(index))).bounds(left + 360, y, 58, 20).build());
		}
		int footerY = Math.min(height - 30, rowY + tasks.size() * 25 + 8);
		addRenderableWidget(Button.builder(Component.literal(CraftListClient.hudVisible() ? "Скрыть HUD" : "Показать HUD"), b -> {
			CraftListClient.toggleHud();
			refresh(() -> {});
		}).bounds(left + 12, footerY, 130, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Готово"), b -> Minecraft.getInstance().setScreenAndShow(null)).bounds(left + PANEL_WIDTH - 112, footerY, 100, 20).build());
	}

	private void refresh(Runnable action) {
		action.run();
		Minecraft.getInstance().setScreenAndShow(new CraftListScreen(search.getValue()));
	}

	@Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int left = (width - PANEL_WIDTH) / 2;
		graphics.fill(left, 18, left + PANEL_WIDTH, height - 14, 0xE6101716);
		graphics.text(font, "CraftList — список крафта", left + 12, 24, 0xFFFFFFFF);
		graphics.text(font, "Рецепты, уже открытые в игре. Нажми на рецепт, чтобы добавить его.", left + 12, 63, 0xFFB7C4BD);
		graphics.text(font, "НАЙДЕННЫЕ РЕЦЕПТЫ", left + 12, 99, 0xFF9CD49D);
		int rowY = 214;
		graphics.text(font, "МОЙ СПИСОК (до 8 рецептов)", left + 12, rowY - 15, 0xFF9CD49D);
		List<CraftListClient.PlanTask> tasks = CraftListClient.tasks();
		for (int i = 0; i < tasks.size(); i++) {
			CraftListClient.PlanTask task = tasks.get(i);
			String name = CraftListClient.output(task).isEmpty() ? task.itemId : CraftListClient.output(task).getHoverName().getString();
			graphics.text(font, name + " × " + task.amount, left + 12, rowY + i * 25 + 6, 0xFFFFFFFF);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override public boolean isPauseScreen() { return false; }
	@Override public void removed() { CraftListClient.setScreenOpen(false); }
}
