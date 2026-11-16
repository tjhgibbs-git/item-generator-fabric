package io.github.tjhgibbs.stacksmith.client;

import io.github.tjhgibbs.stacksmith.client.ui.Chip;
import io.github.tjhgibbs.stacksmith.client.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** A question with a few answers, each saying plainly what it does; Cancel goes back. */
public final class ChoiceScreen extends StacksmithScreen {
    public record Choice(String label, String explanation, int color, Runnable action) {}

    private final String question;
    private final List<Choice> choices;

    public ChoiceScreen(Screen parent, String title, String question, List<Choice> choices) {
        super(Component.literal(title), parent);
        this.question = question;
        this.choices = choices;
    }

    private int panelW() { return Math.min(width - 2 * PAD, 320); }
    private int top() { return Math.max(10, (height - (50 + choices.size() * 44 + 24)) / 2); }

    @Override
    protected void init() {
        int w = panelW() - 20, x = (width - w) / 2, y = top() + 40;
        for (Choice c : choices) {
            addRenderableWidget(Chip.of(c.label(), w, 20, c.action()).primary(c.color()).at(x, y));
            y += 44;
        }
        addRenderableWidget(Chip.of("Cancel", 60, 18, this::onClose).at(x + w - 60, y));
    }

    @Override
    protected void extractPanels(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int pw = panelW(), px = (width - pw) / 2, w = pw - 20, x = px + 10, y = top();
        panel(g, px, y, pw, 50 + choices.size() * 44 + 24);
        g.text(font, font.plainSubstrByWidth(title.getString(), w), x, y + 10, Theme.ACCENT, false);
        g.text(font, font.plainSubstrByWidth(question, w), x, y + 24, Theme.TEXT, false);
        int cy = y + 40;
        for (Choice c : choices) {
            int ly = cy + 23;
            for (var line : font.split(Component.literal(c.explanation()), w)) {
                g.text(font, line, x, ly, Theme.TEXT_DIM, false);
                ly += 10;
            }
            cy += 44;
        }
    }
}
