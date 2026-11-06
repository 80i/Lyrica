package net.lyrica.v26_1.stage;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The stage's own vertex buffers, drawn once a frame. The game's shared source flushes at every switch of render type,
 * which would mean a draw call per glyph.
 *
 * <p>The passes fix the order of drawing whatever the order of writing: shadows lie on what is already there, halos go
 * behind the letters because the letters' soft edges would otherwise cut dark rims into them, the faint through-walls
 * copy goes under the real letters, opaque letters and then their soft rims go next, then the see-through letters one
 * line at a time, far to near, each as depth first and colour after, and twinkles go over everything.
 */
public final class StageBuffers {

  /** Lines fading at the same time beyond this many share the nearest layer. */
  static final int FADE_LAYERS = 4;

  private static final Identifier WHITE = Identifier.fromNamespaceAndPath("lyrica", "white");

  public final Pass shadow = new Pass();
  public final Pass halo = new Pass();
  public final Pass faint = new Pass();
  public final Pass solid = new Pass();
  public final Pass text = new Pass();
  public final Pass top = new Pass();

  private final Pass letterPass = new Pass();
  private final Pass rimPass = new Pass();
  private final Pass faintLetterPass = new Pass();
  private final RenderType letterType;

  final VertexSink letters;
  final VertexSink rims;
  final VertexSink faintLetters;

  private final FadeLayer[] fades = new FadeLayer[FADE_LAYERS];

  public StageBuffers() {
    NativeImage pixel = new NativeImage(1, 1, false);
    pixel.setPixel(0, 0, 0xFFFFFFFF);
    Minecraft.getInstance().getTextureManager().register(WHITE, new DynamicTexture(() -> "Lyrica white", pixel));
    this.letterType = RenderTypes.text(WHITE);
    this.letters = new VertexSink(this.letterPass, this.letterType);
    this.rims = new VertexSink(this.rimPass, this.letterType);
    this.faintLetters = new VertexSink(this.faintLetterPass, RenderTypes.textSeeThrough(WHITE));
  }

  /** Before anything is written this frame. */
  void open() {
    this.letters.open();
    this.rims.open();
    this.faintLetters.open();
    for (int i = 0; i < FADE_LAYERS; i++) {
      if (this.fades[i] != null) {
        this.fades[i].opened = false;
      }
    }
  }

  /** The see-through line at this place in the far-to-near order. Lines take a pass of their own only when needed. */
  FadeLayer fade(int order) {
    int at = Math.min(order, FADE_LAYERS - 1);
    FadeLayer layer = this.fades[at];
    if (layer == null) {
      layer = new FadeLayer(this.letterType);
      this.fades[at] = layer;
    }

    if (!layer.opened) {
      layer.open();
    }

    return layer;
  }

  public void drawAll() {
    this.shadow.draw();
    this.halo.draw();
    this.faint.draw();
    this.faintLetterPass.draw();
    this.solid.draw();
    this.letterPass.draw();
    this.text.draw();
    this.rimPass.draw();
    for (int i = 0; i < FADE_LAYERS; i++) {
      if (this.fades[i] != null && this.fades[i].opened) {
        this.fades[i].draw();
      }
    }

    this.top.draw();
  }

  /** One see-through line: the nearest surface in depth first, so that only it takes colour. */
  static final class FadeLayer {

    private final Pass maskPass = new Pass();
    private final Pass colorPass = new Pass();
    private final Pass rimPass = new Pass();
    final VertexSink.Mask mask;
    final VertexSink color;
    final VertexSink rim;
    boolean opened;

    FadeLayer(RenderType letterType) {
      this.mask = new VertexSink.Mask(this.maskPass, RenderTypes.waterMask());
      this.color = new VertexSink(this.colorPass, letterType);
      this.rim = new VertexSink(this.rimPass, letterType);
    }

    void open() {
      this.opened = true;
      this.mask.open();
      this.color.open();
      this.rim.open();
    }

    void draw() {
      this.maskPass.draw();
      this.colorPass.draw();
      this.rimPass.draw();
    }
  }

  /** One step of the frame: a buffer per render type, made the first time that type shows up. */
  public static final class Pass implements MultiBufferSource {

    private static final int FIRST_BUFFER_BYTES = 64 * 1024;
    private static final int UNUSED_SHARED_BYTES = 256;

    // The source keeps this very map, so a type added later is one it knows. Font atlas pages come and go with the
    // text, and any type not in the map would fall back to the flushing behaviour this class exists to avoid.
    private final SequencedMap<RenderType, ByteBufferBuilder> own = new LinkedHashMap<>();
    private final MultiBufferSource.BufferSource source =
        MultiBufferSource.immediateWithBuffers(this.own, new ByteBufferBuilder(UNUSED_SHARED_BYTES));

    private RenderType lastType;
    private VertexConsumer lastConsumer;

    @Override
    public VertexConsumer getBuffer(RenderType type) {
      if (type == this.lastType) {
        return this.lastConsumer;
      }

      if (!this.own.containsKey(type)) {
        this.own.put(type, new ByteBufferBuilder(FIRST_BUFFER_BYTES));
      }

      this.lastType = type;
      this.lastConsumer = this.source.getBuffer(type);
      return this.lastConsumer;
    }

    /** Writes a font's glyphs and effects into this pass. */
    net.minecraft.client.gui.Font.GlyphVisitor visitor(org.joml.Matrix4fc matrix, net.minecraft.client.gui.Font.DisplayMode mode,
        int light) {
      return net.minecraft.client.gui.Font.GlyphVisitor.forMultiBufferSource(this, new org.joml.Matrix4f(matrix), mode, light);
    }

    void draw() {
      this.lastType = null;
      this.lastConsumer = null;
      this.source.endBatch();
    }
  }
}
