package uz.habibi.bakery.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Rasmlar (mahsulot rasmlari, bosh sahifa rasmi) Telegram serverida saqlanadi — admin ularni botga yuboradi.
 * Bu yerda ular yuklab olinib, xotirada keshlanadi.
 */
@Service
public class ProductPhotoService {

    private static final Logger log = LoggerFactory.getLogger(ProductPhotoService.class);

    /** Mini App uchun rasmning eng uzun tomoni (px) va JPEG sifati — tunnel/sekin internetda tez ochilsin. */
    private static final int MAX_SIDE = 720;
    private static final float QUALITY = 0.75f;

    private final MenuService menu;
    private final HomeBannerService banner;
    private final ObjectProvider<TelegramClient> telegram;
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    public ProductPhotoService(MenuService menu, HomeBannerService banner,
                               ObjectProvider<TelegramClient> telegram) {
        this.menu = menu;
        this.banner = banner;
        this.telegram = telegram;
    }

    /** Bot ishga tushgach barcha rasmlarni oldindan yuklab, kichraytirib qo'yadi — Mini App birinchi ochilishda kutmasin. */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            int n = 0;
            for (var p : menu.allProducts()) {
                if (p.getPhotoFileId() != null && download(p.getPhotoFileId()).isPresent()) {
                    n++;
                }
            }
            loadBanner();
            log.info("Rasmlar oldindan yuklandi: {} ta", n);
        } catch (Exception e) {
            log.warn("Rasmlarni oldindan yuklab bo'lmadi: {}", e.getMessage());
        }
    }

    public Optional<byte[]> load(Long productId) {
        return download(menu.photoFileId(productId));
    }

    public Optional<byte[]> loadBanner() {
        return download(banner.fileId().orElse(null));
    }

    private Optional<byte[]> download(String fileId) {
        TelegramClient client = telegram.getIfAvailable();
        if (fileId == null || client == null) {
            return Optional.empty();
        }
        byte[] cached = cache.get(fileId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            var file = client.execute(GetFile.builder().fileId(fileId).build());
            try (InputStream in = client.downloadFileAsStream(file)) {
                byte[] bytes = shrink(in.readAllBytes());
                cache.put(fileId, bytes);
                return Optional.of(bytes);
            }
        } catch (Exception e) {
            log.warn("Rasmni Telegram'dan yuklab bo'lmadi: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Rasmni MAX_SIDE gacha kichraytirib, JPEG qilib siqadi. Xato bo'lsa — asl rasm qaytadi. */
    static byte[] shrink(byte[] original) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(original));
            if (src == null) {
                return original;
            }
            int w = src.getWidth(), h = src.getHeight();
            double k = Math.min(1.0, (double) MAX_SIDE / Math.max(w, h));
            int nw = Math.max(1, (int) Math.round(w * k)), nh = Math.max(1, (int) Math.round(h * k));
            BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, nw, nh, null);
            g.dispose();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(buf)) {
                writer.setOutput(ios);
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(QUALITY);
                writer.write(null, new IIOImage(out, null, null), param);
            } finally {
                writer.dispose();
            }
            byte[] result = buf.toByteArray();
            return result.length < original.length ? result : original;
        } catch (Exception e) {
            log.warn("Rasmni kichraytirib bo'lmadi: {}", e.getMessage());
            return original;
        }
    }
}
