package fear.client.gui.font;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import fear.client.utility.render.Render2DEngine;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

import static fear.client.core.manager.IManager.mc;

class GlyphMap {
    final char fromIncl, toExcl;
    final Font font;
    final Identifier bindToTexture;
    final int pixelPadding;
    int width = 1, height = 1;
    boolean generated = false;

    private final Map<Character, Glyph> glyphCache = new HashMap<>();

    public GlyphMap(char from, char to, Font font, Identifier identifier, int padding) {
        this.fromIncl = from;
        this.toExcl = to;
        this.font = font;
        this.bindToTexture = identifier;
        this.pixelPadding = padding;
    }

    public Glyph getGlyph(char c) {
        if (!generated) {
            generate();
        }
        return glyphCache.get(c);
    }

    public void destroy() {
        generated = false;
        glyphCache.clear();
    }

    public boolean contains(char c) {
        return c >= fromIncl && c < toExcl;
    }

    public void generate() {
        if (generated) return;
        generated = true;

        // Measure all characters to find atlas size
        BufferedImage dummy = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D dg = dummy.createGraphics();
        dg.setFont(font);
        FontMetrics fm = dg.getFontMetrics();

        int cols = 16;
        int rows = (int) Math.ceil((double)(toExcl - fromIncl) / cols);

        int cellW = 0, cellH = fm.getHeight() + pixelPadding * 2;
        for (char c = fromIncl; c < toExcl; c++) {
            int w = fm.charWidth(c) + pixelPadding * 2;
            if (w > cellW) cellW = w;
        }
        dg.dispose();

        if (cellW <= 0) cellW = fm.getHeight();

        this.width = cols * cellW;
        this.height = rows * cellH;

        if (this.width <= 0 || this.height <= 0) return;

        BufferedImage image = new BufferedImage(this.width, this.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setFont(font);
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, this.width, this.height);
        g.setColor(Color.WHITE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        FontMetrics metrics = g.getFontMetrics();

        for (int i = 0; i < (toExcl - fromIncl); i++) {
            char c = (char)(fromIncl + i);
            int col = i % cols;
            int row = i / cols;

            int x = col * cellW + pixelPadding;
            int y = row * cellH + pixelPadding;

            int charW = metrics.charWidth(c);
            int charH = metrics.getHeight();

            g.drawString(String.valueOf(c), x, y + metrics.getAscent());

            glyphCache.put(c, new Glyph(x, y, charW + pixelPadding, charH, c, this));
        }
        g.dispose();

        // Register texture on render thread
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            byte[] bytes = baos.toByteArray();
            java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(bytes.length).put(bytes);
            buf.flip();
            mc.execute(() -> {
                try {
                    NativeImageBackedTexture tex = new NativeImageBackedTexture(NativeImage.read(buf));
                    mc.getTextureManager().registerTexture(bindToTexture, tex);
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    public static void registerBufferedImageTexture(Identifier i, BufferedImage bi) {
        Render2DEngine.registerBufferedImageTexture(new Texture(i), bi);
    }
}
