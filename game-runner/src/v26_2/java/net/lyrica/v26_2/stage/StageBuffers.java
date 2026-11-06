package net.lyrica.v26_2.stage;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.buffers.GpuBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;

/**
 * The stage's own vertex buffers, drawn once a frame in one render pass of their own.
 *
 * <p>The passes fix the order of drawing whatever the order of writing: shadows lie on what is already there, halos go
 * behind the letters because the letters' soft edges would otherwise cut dark rims into them, the faint through-walls
 * copy goes under the real letters, opaque letters and then their soft rims go next, then the see-through letters one
 * line at a time, far to near, each as depth first and colour after, and twinkles go over everything.
 */
public final class StageBuffers {

  /** Lines fading at the same time beyond this many share the nearest layer. */
  static final int FADE_LAYERS = 4;

  // Closed once the pass that draws from them has ended
  private static final List<GpuBuffer> FRAME_BUFFERS = new ArrayList<>();
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
    Pass[] order = {this.shadow, this.halo, this.faint, this.faintLetterPass, this.solid, this.letterPass, this.text,
        this.rimPass};
    for (Pass pass : order) {
      pass.request();
    }

    for (int i = 0; i < FADE_LAYERS; i++) {
      if (this.fades[i] != null && this.fades[i].opened) {
        this.fades[i].request();
      }
    }

    this.top.request();
    {
      for (Pass pass : order) {
        pass.draw();
      }

      for (int i = 0; i < FADE_LAYERS; i++) {
        if (this.fades[i] != null && this.fades[i].opened) {
          this.fades[i].draw();
        }
      }

      this.top.draw();
    }

    for (int i = 0; i < FRAME_BUFFERS.size(); i++) {
      FRAME_BUFFERS.get(i).close();
    }

    FRAME_BUFFERS.clear();
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

    void request() {
      this.maskPass.request();
      this.colorPass.request();
      this.rimPass.request();
    }

    void draw() {
      this.maskPass.draw();
      this.colorPass.draw();
      this.rimPass.draw();
    }
  }

  /**
   * One step of the frame: a buffer per render type, written in any order. The game's staged buffer takes one open draw at
   * a time, and letters write to several at once, so each frame's vertices become a buffer of their own when drawn.
   */
  public static final class Pass {

    private static final int FIRST_BUFFER_BYTES = 64 * 1024;

    private final Map<RenderType, Writing> writing = new LinkedHashMap<>();
    private RenderType lastType;
    private VertexConsumer lastConsumer;

    public VertexConsumer getBuffer(RenderType type) {
      if (type == this.lastType) {
        return this.lastConsumer;
      }

      Writing into = this.writing.get(type);
      if (into == null) {
        into = new Writing(new ByteBufferBuilder(FIRST_BUFFER_BYTES));
        this.writing.put(type, into);
      }

      if (into.builder == null) {
        into.builder = new BufferBuilder(into.bytes, type.primitiveTopology(), type.format());
      }

      this.lastType = type;
      this.lastConsumer = into.builder;
      return into.builder;
    }

    /** Writes a font's glyphs and effects into this pass. */
    Font.GlyphVisitor visitor(Matrix4fc matrix, Font.DisplayMode mode, int light) {
      return new Font.GlyphVisitor() {
        @Override
        public void acceptGlyph(TextRenderable.Styled glyph) {
          this.acceptRenderable(glyph);
        }

        @Override
        public void acceptEffect(TextRenderable effect) {
          this.acceptRenderable(effect);
        }

        @Override
        public void acceptRenderable(TextRenderable text) {
          text.render(matrix, Pass.this.getBuffer(text.renderType(mode)), light, false);
        }
      };
    }

    void request() {
    }

    void draw() {
      this.lastType = null;
      this.lastConsumer = null;
      for (Map.Entry<RenderType, Writing> entry : this.writing.entrySet()) {
        Writing written = entry.getValue();
        if (written.builder == null) {
          continue;
        }

        try (MeshData mesh = written.builder.build()) {
          written.builder = null;
          if (mesh == null) {
            continue;
          }

          MeshData.DrawState state = mesh.drawState();
          GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> "Lyrica letters", GpuBuffer.USAGE_VERTEX,
              mesh.vertexBuffer());
          FRAME_BUFFERS.add(vertices);
          RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(state.primitiveTopology());
          indices.getBuffer(state.indexCount());
          entry.getKey().prepare().drawFromBuffer(new StagedVertexBuffer.ExecuteInfo(vertices, indices.getBuffer(state.indexCount()), indices.type(), 0, 0,
              state.indexCount()));
        }
      }
    }

    private static final class Writing {

      final ByteBufferBuilder bytes;
      BufferBuilder builder;

      Writing(ByteBufferBuilder bytes) {
        this.bytes = bytes;
      }
    }
  }
}
