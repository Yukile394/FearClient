package fear.client.gui.font;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.chars.Char2ObjectArrayMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryUtil;
import fear.client.injection.accesors.INativeImage;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

class GlyphMap {
    final char fromIncl, toExcl;
    final Font font;
    final Identifier bindToTexture;
    final int pixelPadding;
    private final Char2ObjectArrayMap<Glyph> glyphs = new Char2ObjectArrayMap<>();
    int width, height;

    boolean generated = false;

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
        return glyphs.get(c);
    }

    public void destroy() {
        MinecraftClient.getInstance().getTextureManager().destroyTexture(this.bindToTexture);
        this.glyphs.clear();
        this.width = -1;
        this.height = -1;
        generated = false;
    }

    public boolean contains(char c) {
        return c >= fromIncl && c < toExcl;
    }

    private Font getFontForGlyph(char c) {
        if (font.canDisplay(c)) {
            return font;
        }
        return this.font; // no font can display it, so it doesn't matter which one we pick; it'll always be missing
    }

    public void generate() {
        if (generated) {
            return;
        }
        int range = toExcl - fromIncl - 1;
        int charsVert = (int) (Math.ceil(Math.sqrt(range)) * 1.5);  // double as many chars wide as high
        glyphs.clear();
        int generatedChars = 0;
        int charNX = 0;
        int maxX = 0, maxY = 0;
        int currentX = 0, currentY = 0;
        int currentRowMaxY = 0;
        List<Glyph> glyphs1 = new ArrayList<>();
        // NOTE: we used to measure glyphs with font.getStringBounds(str, new FontRenderContext(...)).
        // On some headless/AWT-emulated environments (e.g. PojavLauncher/caciocavallo on Android),
        // that path internally calls HeadlessGraphicsEnvironment.getDefaultScreenDevice(), which is
        // not implemented and throws java.awt.HeadlessException, crashing the game on first render.
        // Measuring through a Graphics2D obtained from an off-screen BufferedImage avoids that
        // screen-device lookup entirely, since the FontRenderContext is then derived from the image.
        BufferedImage metricsImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D metricsG2D = metricsImage.createGraphics();
        metricsG2D.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        while (generatedChars <= range) {
            char currentChar = (char) (fromIncl + generatedChars);
            Font font = getFontForGlyph(currentChar);
            metricsG2D.setFont(font);
            Rectangle2D stringBounds = metricsG2D.getFontMetrics().getStringBounds(String.valueOf(currentChar), metricsG2D);

            int width = (int) Math.ceil(stringBounds.getWidth());
            int height = (int) Math.ceil(stringBounds.getHeight());
            generatedChars++;
            maxX = Math.max(maxX, currentX + width);
            maxY = Math.max(maxY, currentY + height);
            if (charNX >= charsVert) {
                currentX = 0;
                currentY += currentRowMaxY + pixelPadding; // add height of highest glyph, and reset
                charNX = 0;
                currentRowMaxY = 0;
            }
            currentRowMaxY = Math.max(currentRowMaxY, height); // calculate the highest glyph in this row
            glyphs1.add(new Glyph(currentX, currentY, width, height, currentChar, this));
            currentX += width + pixelPadding;
            charNX++;
        }
        metricsG2D.dispose();
        BufferedImage bi = new BufferedImage(Math.max(maxX + pixelPadding, 1), Math.max(maxY + pixelPadding, 1),
                BufferedImage.TYPE_INT_ARGB);
        width = bi.getWidth();
        height = bi.getHeight();
        Graphics2D g2d = bi.createGraphics();
        g2d.setColor(new Color(255, 255, 255, 0));
        g2d.fillRect(0, 0, width, height);
        g2d.setColor(Color.WHITE);

        g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        for (Glyph glyph : glyphs1) {
            g2d.setFont(getFontForGlyph(glyph.value()));
            FontMetrics fontMetrics = g2d.getFontMetrics();
            g2d.drawString(String.valueOf(glyph.value()), glyph.u(), glyph.v() + fontMetrics.getAscent());
            glyphs.put(glyph.value(), glyph);
        }
        registerBufferedImageTexture(bindToTexture, bi);
        generated = true;
    }

    public static void registerBufferedImageTexture(Identifier i, BufferedImage bi) {
        try {
            // argb from BufferedImage is little endian, alpha is actually where the `a` is in the label
            // rgba from NativeImage (and by extension opengl) is big endian, alpha is on the other side (abgr)
            // thank you opengl
            int ow = bi.getWidth();
            int oh = bi.getHeight();
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, ow, oh, false);
            @SuppressWarnings("DataFlowIssue") long ptr = ((INativeImage) (Object) image).getPointer();
            IntBuffer backingBuffer = MemoryUtil.memIntBuffer(ptr, image.getWidth() * image.getHeight());
            int off = 0;
            Object _d;
            WritableRaster _ra = bi.getRaster();
            ColorModel _cm = bi.getColorModel();
            int nbands = _ra.getNumBands();
            int dataType = _ra.getDataBuffer().getDataType();
            _d = switch (dataType) {
                case DataBuffer.TYPE_BYTE -> new byte[nbands];
                case DataBuffer.TYPE_USHORT -> new short[nbands];
                case DataBuffer.TYPE_INT -> new int[nbands];
                case DataBuffer.TYPE_FLOAT -> new float[nbands];
                case DataBuffer.TYPE_DOUBLE -> new double[nbands];
                default -> throw new IllegalArgumentException("Unknown data buffer type: " +
                        dataType);
            };

            for (int y = 0; y < oh; y++) {
                for (int x = 0; x < ow; x++) {
                    _ra.getDataElements(x, y, _d);
                    int a = _cm.getAlpha(_d);
                    int r = _cm.getRed(_d);
                    int g = _cm.getGreen(_d);
                    int b = _cm.getBlue(_d);
                    int abgr = a << 24 | b << 16 | g << 8 | r;
                    backingBuffer.put(abgr);
                }
            }
            NativeImageBackedTexture tex = new NativeImageBackedTexture(image);
            tex.upload();
            if (RenderSystem.isOnRenderThread()) {
                MinecraftClient.getInstance().getTextureManager().registerTexture(i, tex);
            } else {
                RenderSystem.recordRenderCall(() -> MinecraftClient.getInstance().getTextureManager().registerTexture(i, tex));
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}