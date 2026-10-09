package com.troblecodings.signals.guis;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.lwjgl.opengl.GL11;

import com.troblecodings.core.I18Wrapper;
import com.troblecodings.guilib.ecs.GuiElements;
import com.troblecodings.guilib.ecs.entitys.BufferWrapper;
import com.troblecodings.guilib.ecs.entitys.DrawInfo;
import com.troblecodings.guilib.ecs.entitys.UIBox;
import com.troblecodings.guilib.ecs.entitys.UIComponent;
import com.troblecodings.guilib.ecs.entitys.UIComponentEntity;
import com.troblecodings.guilib.ecs.entitys.UIEntity;
import com.troblecodings.guilib.ecs.entitys.UIEntity.KeyEvent;
import com.troblecodings.guilib.ecs.entitys.UIScrollBox;
import com.troblecodings.guilib.ecs.entitys.input.UIClickable;
import com.troblecodings.guilib.ecs.entitys.input.UIScroll;
import com.troblecodings.guilib.ecs.entitys.render.UIColor;
import com.troblecodings.guilib.ecs.entitys.render.UIReentrantScissor;
import com.troblecodings.guilib.ecs.entitys.render.UIScissor;
import com.troblecodings.guilib.ecs.entitys.render.UIToolTip;
import com.troblecodings.guilib.ecs.entitys.transform.UIIndependentTranslate;
import com.troblecodings.guilib.ecs.entitys.transform.UIRotate;
import com.troblecodings.signals.enums.EnumGuiMode;

import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.Rotation;

public class UIMenu extends UIComponentEntity {

    public static final int BACKGROUND_COLOR = 0xFFAFAFAF;
    public static final int HIGHLIGHT_COLOR = 0x45339933;

    private static final String[] MOVE_ICON = {
            ".....#.....",
            "....###....",
            ".....#.....",
            ".....#.....",
            ".#.......#.",
            "####...####",
            ".#.......#.",
            ".....#.....",
            ".....#.....",
            "....###....",
            ".....#.....",
    };
    private static final float MOVE_ICON_PIXEL = 1.5f;
    private static final int MOVE_ICON_COLOR = 0xFF000000;

    private final Map<EnumGuiMode, UIEntity> modeForEntity = new HashMap<>();
    private UIEntity moveToolEntity;
    private boolean moveTool = false;
    private Consumer<Boolean> moveToolConsumer = (active) -> {
    };

    private final UIRotate rotate = new UIRotate();
    private int selection = 0;
    private int rotation = 0;
    private BiConsumer<Integer, Integer> consumer = (i1, i2) -> {
    };

    public UIMenu() {
        super(new UIEntity());
        entity.setInheritWidth(true);
        entity.setX(2);
        entity.setY(2);
        entity.add(new UIBox(UIBox.VBOX, 0));
        entity.add(new UIScissor());

        final UIEntity list = new UIEntity();
        entity.add(list);
        list.setInheritWidth(true);
        list.setHeight(21);

        final UIScrollBox scrollbox = new UIScrollBox(UIBox.HBOX, 2);
        list.add(scrollbox);

        moveToolEntity = new UIEntity();
        moveToolEntity.add(new UIColor(BACKGROUND_COLOR));
        moveToolEntity.add(new UIReentrantScissor());
        moveToolEntity.add(getMoveToolIcon());
        moveToolEntity.setHeight(20);
        moveToolEntity.setWidth(20);
        moveToolEntity.add(new UIClickable(e -> selectMoveTool()));
        moveToolEntity.add(new UIToolTip(I18Wrapper.format("info.editor.movetool")));
        list.add(moveToolEntity);

        for (final EnumGuiMode mode : EnumGuiMode.values()) {
            final UIEntity preview = new UIEntity();
            preview.add(new UIColor(BACKGROUND_COLOR));
            preview.add(new UIReentrantScissor());

            preview.add(new UIIndependentTranslate(10, 10, 0));
            preview.add(rotate);
            preview.add(new UIIndependentTranslate(-10, -10, 0));

            final UIComponent sbt = SidePanel.fromEnum(mode.ordinal(), rotation, 1.95f);
            preview.add(sbt);
            preview.setHeight(20);
            preview.setWidth(20);
            preview.add(new UIClickable(e -> updateSelection(mode)));
            if (mode.ordinal() == this.selection)
                preview.add(new UIColor(HIGHLIGHT_COLOR));

            list.add(preview);
            modeForEntity.put(mode, preview);
        }
        final UIScroll scroll = new UIScroll();
        final UIEntity scrollBar = GuiElements.createScrollBar(scrollbox, 10, scroll);
        scrollbox.setConsumer(i -> {
        });
        entity.add(scroll);
        entity.add(scrollBar);
    }

    private static UIComponent getMoveToolIcon() {
        return new UIComponent() {

            @Override
            public void draw(final DrawInfo info) {
                if (this.parent == null)
                    return;
                final float offset = (20.0f - MOVE_ICON.length * MOVE_ICON_PIXEL) / 2.0f;
                info.push();
                info.translate(offset, offset, 0);
                info.alphaOn();
                info.blendOn();
                info.applyColor();
                final BufferWrapper wrapper = info.builder(GL11.GL_QUADS,
                        DefaultVertexFormats.POSITION_COLOR);
                for (int y = 0; y < MOVE_ICON.length; y++) {
                    final String row = MOVE_ICON[y];
                    for (int x = 0; x < row.length(); x++) {
                        if (row.charAt(x) != '#')
                            continue;
                        wrapper.quad(x * MOVE_ICON_PIXEL, (x + 1) * MOVE_ICON_PIXEL,
                                y * MOVE_ICON_PIXEL, (y + 1) * MOVE_ICON_PIXEL, MOVE_ICON_COLOR);
                    }
                }
                info.end();
                info.pop();
            }
        };
    }

    private void removeHighlight(final UIEntity highlighted) {
        if (highlighted == null)
            return;
        highlighted.findRecursive(UIColor.class).forEach(c -> {
            if (c.getColor() == HIGHLIGHT_COLOR)
                highlighted.remove(c);
        });
    }

    private void selectMoveTool() {
        if (moveTool)
            return;
        removeHighlight(modeForEntity.get(EnumGuiMode.values()[selection]));
        moveToolEntity.add(new UIColor(HIGHLIGHT_COLOR));
        moveTool = true;
        moveToolConsumer.accept(true);
    }

    public void setMoveToolConsumer(final Consumer<Boolean> consumer) {
        this.moveToolConsumer = consumer;
    }

    private void updateSelection(final EnumGuiMode newMode) {
        if (moveTool) {
            removeHighlight(moveToolEntity);
            moveTool = false;
            moveToolConsumer.accept(false);
        } else {
            removeHighlight(modeForEntity.get(EnumGuiMode.values()[selection]));
        }
        final UIEntity newEntity = modeForEntity.get(newMode);
        newEntity.add(new UIColor(HIGHLIGHT_COLOR));
        this.selection = newMode.ordinal();
        consumer.accept(selection, rotation);
    }

    public int getSelection() {
        return selection;
    }

    public void setConsumer(final BiConsumer<Integer, Integer> consumer) {
        this.consumer = consumer;
    }

    @Override
    public void update() {
        this.entity.setHeight(this.parent.getHeight());
        this.entity.setWidth(this.parent.getWidth() - 4);
        this.entity.update();
    }

    @Override
    public void onAdd(final UIEntity entity) {
        super.onAdd(entity);
        this.entity.onAdd(entity);
        this.entity.updateEvent(entity.getLastUpdateEvent());
    }

    @Override
    public void keyEvent(final KeyEvent event) {
        super.keyEvent(event);
        if (event.typedChar == 'R' || event.typedChar == 'r') {
            this.rotation++;
            if (this.rotation >= Rotation.values().length)
                this.rotation = 0;
            consumer.accept(selection, rotation);
            rotate.setRotateZ(rotation * UIRotate.PERPENDICULAR_ANGLE);
        }
    }

    public int getRotation() {
        return rotation;
    }
}