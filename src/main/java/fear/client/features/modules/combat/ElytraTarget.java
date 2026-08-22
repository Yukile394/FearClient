package fear.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import fear.client.events.impl.EventPostSync;
import fear.client.features.modules.Module;
import fear.client.setting.Setting;
import fear.client.utility.Timer;
import fear.client.utility.player.InventoryUtility;
import fear.client.utility.player.PlayerUtility;
import fear.client.utility.player.SearchInvResult;

import java.util.Random;

public final class ElytraTarget extends Module {

    private static final Random RNG = new Random();

    /* ════════════════════════════════════════════════
     *  ROKET AYARLARI
     *  Fişek atışını kontrol eder.
     * ════════════════════════════════════════════════ */

    /** Roketleri otomatik ateşle. */
    private final Setting<Boolean> roketGuclendir   = new Setting<>("RoketGuclendir",   true);

    /**
     * İki roket atışı arasındaki bekleme süresi (ms).
     * Düşük değer = daha sık ateş.  Anti-cheat için 80 ms'nin altına inme.
     */
    private final Setting<Integer> roketGecikmesi   = new Setting<>("RoketGecikmesi",   160,  40, 1200,
            v -> roketGuclendir.getValue());

    /**
     * Her tetiklemede kaç roket atılır.
     * 1 en güvenli, 2-3 daha agresif.
     */
    private final Setting<Integer> roketPatlamasi   = new Setting<>("RoketPatlamasi",   1,    1,  3,
            v -> roketGuclendir.getValue());

    /** Slot değişimini pakette gizle (sunucu animasyon görmez). */
    private final Setting<Boolean> sessizRoket      = new Setting<>("SessizRoket",      true,
            v -> roketGuclendir.getValue());

    /** Hotbar'da roket yoksa envanterde ara ve geç. */
    private final Setting<Boolean> otomatikRoketSec = new Setting<>("OtomatikRoketSec", true,
            v -> roketGuclendir.getValue());

    /** Hedef olmadan da sürekli güclendir. */
    private final Setting<Boolean> hepGuclendir     = new Setting<>("HepGuclendir",     false,
            v -> roketGuclendir.getValue());

    /* ════════════════════════════════════════════════
     *  HEDEF AYARLARI
     *  Kimi ve nasıl takip edeceğini belirler.
     * ════════════════════════════════════════════════ */

    /** Bu mesafedeki oyuncuları hedef al (blok). */
    private final Setting<Float>   hedefMenzili     = new Setting<>("HedefMenzili",     48f,  5f, 96f);

    /** Yalnızca biz elytra ile uçarken aktif ol. */
    private final Setting<Boolean> sadeceSakinken   = new Setting<>("SadeceSakinken",   true);

    /** Hedefin etrafında yörüngede dön. */
    private final Setting<Boolean> hedefiTakipEt    = new Setting<>("HedefiTakipEt",    true);

    /**
     * Yörünge ve intercept hızı çarpanı.
     * 0.10 = çok yavaş, 0.50 = normal, 1.0 = hızlı.
     * Anti-cheat için 0.40'ın altında tut.
     */
    private final Setting<Float>   takipHizi        = new Setting<>("TakipHizi",        0.22f, 0.05f, 1.0f,
            v -> hedefiTakipEt.getValue());

    /**
     * Hedeften kaç blok uzakta yörüngede dönülür.
     * Büyük değer = geniş daire, küçük = yakın sarma.
     */
    private final Setting<Float>   yorungeCapi      = new Setting<>("YorungeCapi",      7.0f, 2.0f, 20.0f,
            v -> hedefiTakipEt.getValue());

    /**
     * Yörünge açısının her tick artış miktarı (radyan).
     * 0.003 = çok yavaş dönüş, 0.06 = çok hızlı sarma.
     * Bypass için 0.012 önerilir.
     */
    private final Setting<Float>   yorungeHizi      = new Setting<>("YorungeHizi",      0.012f, 0.002f, 0.06f,
            v -> hedefiTakipEt.getValue());

    /** Hedef elytra ile kaçıyorsa önüne geç. */
    private final Setting<Boolean> hedefinOnuneGec  = new Setting<>("HedefinOnuneGec",  true,
            v -> hedefiTakipEt.getValue());

    /**
     * Tahmin kaç tick ileriye bakacak.
     * 1.0 = normal; 2-3 = kaçan hedef için agresif tahmin.
     */
    private final Setting<Float>   tahminCarpani    = new Setting<>("TahminCarpani",    1.2f, 0.2f, 5.0f,
            v -> hedefiTakipEt.getValue() && hedefinOnuneGec.getValue());

    /**
     * Hedefin kaç blok yukarısını hedefle.
     * Pozitif = üstünden geç, negatif = altından.
     */
    private final Setting<Float>   dikeyOffset      = new Setting<>("DikeyOffset",      1.5f, -8.0f, 12.0f,
            v -> hedefiTakipEt.getValue());

    /* ════════════════════════════════════════════════
     *  ROTASYON AYARLARI
     *  Görüş açısı ne kadar hızlı döner.
     * ════════════════════════════════════════════════ */

    /**
     * Her tick bakış ne kadar yumuşatılır (0-1).
     * 0.05 = çok yumuşak/yavaş, 0.50 = sert.
     * Bypass için 0.08-0.15 arası önerilir.
     */
    private final Setting<Float>   donusYumusatma   = new Setting<>("DonusYumusatma",   0.10f, 0.02f, 0.50f);

    /**
     * Bir tick'te bakışın en fazla kaç derece döneceği.
     * Anti-cheat bypass için kritik. 15 altı çok güvenli.
     */
    private final Setting<Float>   maxDerecePerTik  = new Setting<>("MaxDerecePerTik",  14f, 2f, 90f);

    /** Rotasyona hafif rastgele titreşim ekle. */
    private final Setting<Boolean> rastgeleRotasyon = new Setting<>("RastgeleRotasyon",  true);

    /**
     * Rastgele titreşim ne kadar güçlü (derece).
     * 0.02 = neredeyse sıfır, 0.15 = belirgin titreşim.
     */
    private final Setting<Float>   rastgeleMiktar   = new Setting<>("RastgeleMiktar",   0.04f, 0.005f, 0.20f,
            v -> rastgeleRotasyon.getValue());

    /* ════════════════════════════════════════════════
     *  KRİTİK VURUŞ
     * ════════════════════════════════════════════════ */

    /** Saldırıdan önce kritik vuruş paketi gönder. */
    private final Setting<Boolean>  otomatikKritik  = new Setting<>("OtomatikKritik",   true);

    /**
     * Paket = minimal Y farkı (çok hafif, sezilmez).
     * Strict = gerçek atlama simüle eder (daha yüksek hasar garantisi).
     */
    private final Setting<KritikMod> kritikModu     = new Setting<>("KritikModu",       KritikMod.Paket,
            v -> otomatikKritik.getValue());

    /**
     * Kritik paketleri arasındaki minimum bekleme (ms).
     * Çok düşürme; sunucu spam fark eder.
     */
    private final Setting<Integer>  kritikGecikmesi = new Setting<>("KritikGecikmesi",  300, 120, 2000,
            v -> otomatikKritik.getValue());

    /* ════════════════════════════════════════════════
     *  BYPASS AYARLARI
     *  Anti-cheat'ten kaçma stratejisi.
     * ════════════════════════════════════════════════ */

    /**
     * Yok   = bypass yok, ham değerler.
     * Hafif = MaxDerecePerTik limiti + hafif gecikme.
     * Agir  = her aksiyona rastgele gecikme, çok yavaş rotasyon.
     */
    private final Setting<BypassSeviye> bypassModu  = new Setting<>("BypassModu",       BypassSeviye.Hafif);

    /** Aksiyonlar arasına insan benzeri rastgele gecikme ekle. */
    private final Setting<Boolean> insansiGecikme   = new Setting<>("InsansiGecikme",   true,
            v -> bypassModu.not(BypassSeviye.Yok));

    /**
     * Rastgele gecikmenin alt sınırı (ms).
     * Bypass modu Agir ise bu değer otomatik 2x uygulanır.
     */
    private final Setting<Integer> gecikmeMin       = new Setting<>("GecikmeMin",        30,  5, 250,
            v -> insansiGecikme.getValue() && bypassModu.not(BypassSeviye.Yok));

    /** Rastgele gecikmenin üst sınırı (ms). */
    private final Setting<Integer> gecikmeMax       = new Setting<>("GecikmeMax",        85, 20, 800,
            v -> insansiGecikme.getValue() && bypassModu.not(BypassSeviye.Yok));

    /* ════════════════════════════════════════════════
     *  KILIC AYARI
     * ════════════════════════════════════════════════ */

    /** Hotbar'daki en keskin kılıca sessizce geç. */
    private final Setting<Boolean> enIyiKilicSec    = new Setting<>("EnIyiKilicSec",    true);

    /* ════════════════════════════════════════════════
     *  YUMUŞAK GEÇİŞ AYARLARI
     *  Ani sıçramaları ve dikkat çeken hareketleri önler.
     * ════════════════════════════════════════════════ */

    /**
     * Hedef kilitlendiği ilk anda tam hızla dönmek yerine
     * birkaç tick boyunca kademeli hızlan.
     */
    private final Setting<Boolean> yumusakBaslangic = new Setting<>("YumusakBaslangic", true);

    /** Kademeli hızlanmanın kaç tick süreceği. */
    private final Setting<Integer> baslangicTikSayisi = new Setting<>("BaslangicTikSayisi", 12, 2, 60,
            v -> yumusakBaslangic.getValue());

    /**
     * Hedefe yaklaştıkça takip hızını otomatik düşür.
     * Aşırı yakında sert dönüşleri ve titremeyi önler.
     */
    private final Setting<Boolean> mesafeyeGoreYavaslat = new Setting<>("MesafeyeGoreYavaslat", true);

    /** Bu mesafenin altında yavaşlama başlar (blok). */
    private final Setting<Float>   yavaslamaMesafesi = new Setting<>("YavaslamaMesafesi", 6f, 1f, 20f,
            v -> mesafeyeGoreYavaslat.getValue());

    /** Yavaşlamanın en fazla ne kadar hız kesebileceği (0-1). */
    private final Setting<Float>   yavaslamaGucu     = new Setting<>("YavaslamaGucu", 0.6f, 0.1f, 0.95f,
            v -> mesafeyeGoreYavaslat.getValue());

    /**
     * Hedef menzil dışına çıktığında veya kaybolduğunda
     * bakışı aniden bırakmak yerine son yönde birkaç tick devam et.
     */
    private final Setting<Boolean> yumusakBirakma   = new Setting<>("YumusakBirakma", true);

    /** Bırakma sonrası kaç tick eski yön korunsun. */
    private final Setting<Integer> birakmaTikSayisi = new Setting<>("BirakmaTikSayisi", 8, 0, 40,
            v -> yumusakBirakma.getValue());

    /* ════════════════════════════════════════════════
     *  İÇ DURUM
     * ════════════════════════════════════════════════ */

    private final Timer roketTimer      = new Timer();
    private final Timer kritikTimer     = new Timer();
    private final Timer hareketTimer    = new Timer();
    private double      yorungeAci      = 0;
    private long        insansiAraliKMs = 55;

    // Yumuşak geçiş durumu
    private int    kilitlenmeTikSayaci = 0;   // hedef kilitlendiğinden bu yana geçen tick
    private int    birakmaSayaci       = 0;   // hedef kaybolduktan sonra kalan tick
    private float  sonYaw, sonPitch;          // bırakma için son bilinen bakış yönü
    private boolean oncekiTikHedefVarMi = false;

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    // ──────────────────────────────────────────────────────────────────
    @EventHandler
    public void onPostSync(EventPostSync e) {
        if (mc.player == null || mc.world == null) return;

        Entity hedef      = Aura.target;
        boolean hedefVarMi = hedef != null
                && PlayerUtility.squaredDistanceFromEyes(hedef.getPos())
                   < (hedefMenzili.getValue() * hedefMenzili.getValue());

        // Sadece elytra uçuşunda aktif
        if (sadeceSakinken.getValue() && !mc.player.isFallFlying()) return;

        // İnsan benzeri gecikme — bypass Yok değilse
        if (bypassModu.not(BypassSeviye.Yok) && insansiGecikme.getValue()) {
            if (!hareketTimer.passedMs(insansiAraliKMs)) return;
            yeniGecikmeHesapla();
        }

        // Kilitlenme/bırakma tick sayaçlarını güncelle
        if (hedefVarMi && !oncekiTikHedefVarMi) {
            kilitlenmeTikSayaci = 0; // yeni hedef, başlangıçtan say
        }
        if (hedefVarMi) {
            kilitlenmeTikSayaci++;
            birakmaSayaci = birakmaTikSayisi.getValue();
        } else if (birakmaSayaci > 0) {
            birakmaSayaci--;
        }
        oncekiTikHedefVarMi = hedefVarMi;

        // Yörüngede takip — hedef varken normal, yoksa bırakma penceresinde son yönü koru
        if (hedefiTakipEt.getValue() && hedefVarMi) {
            takipVeYorunge(hedef);
        } else if (hedefiTakipEt.getValue() && yumusakBirakma.getValue() && birakmaSayaci > 0) {
            // Son bilinen yöne doğru gittikçe zayıflayan bir çekiş uygula (ani kesilme yok)
            float sonrakiYumusatma = donusYumusatma.getValue() * 0.4f;
            float mevcutYaw   = mc.player.getYaw();
            float mevcutPitch = mc.player.getPitch();
            float yawFarki   = MathHelper.wrapDegrees(sonYaw   - mevcutYaw)   * sonrakiYumusatma;
            float pitchFarki = MathHelper.wrapDegrees(sonPitch - mevcutPitch) * sonrakiYumusatma;
            mc.player.setYaw(mevcutYaw + yawFarki);
            mc.player.setPitch(mevcutPitch + pitchFarki);
        }

        // En keskin kılıç
        if (enIyiKilicSec.getValue() && hedefVarMi) {
            SearchInvResult kilic = InventoryUtility.getHighestSharpnessSwordHotBar();
            if (kilic.found() && mc.player.getInventory().selectedSlot != kilic.slot()) {
                sendPacket(new UpdateSelectedSlotC2SPacket(kilic.slot()));
            }
        }

        // Kritik vuruş
        if (otomatikKritik.getValue() && hedefVarMi
                && kritikTimer.passedMs(kritikGecikmesi.getValue())) {
            kritikPaketAt();
            kritikTimer.reset();
        }

        // Roket boost
        if (!roketGuclendir.getValue()) return;
        boolean guclendirilecekMi = hepGuclendir.getValue() || hedefVarMi;
        if (!guclendirilecekMi) return;

        long etkinRoketGecikmesi = roketGecikmesi.getValue();
        if (bypassModu.is(BypassSeviye.Agir)) {
            // Ağır bypass: gecikmeye ekstra rastgele pay koy
            etkinRoketGecikmesi += (long)(RNG.nextFloat() * 60);
        }

        if (!roketTimer.passedMs(etkinRoketGecikmesi)) return;

        for (int i = 0; i < roketPatlamasi.getValue(); i++) {
            roketAt();
        }
        roketTimer.reset();
    }

    // ──────────────────────────────────────────────────────────────────

    /**
     * Hedefin etrafında yörüngede döner.
     * Rotasyon hızı MaxDerecePerTik ile sınırlandırılır.
     */
    private void takipVeYorunge(Entity hedef) {
        Vec3d hedefKonum  = hedef.getPos();
        Vec3d hedefHizi   = hedef.getVelocity();
        float yaricap     = yorungeCapi.getValue();
        float hiz         = takipHizi.getValue();

        // Yumuşak başlangıç: kilitlenmenin ilk tick'lerinde hızı kademeli artır
        if (yumusakBaslangic.getValue()) {
            int toplamTik = Math.max(1, baslangicTikSayisi.getValue());
            float oran = MathHelper.clamp((float) kilitlenmeTikSayaci / toplamTik, 0f, 1f);
            // Ease-out eğrisi: başta çok yavaş, sona doğru normal hıza ulaşır
            float easeOran = 1f - (1f - oran) * (1f - oran);
            hiz *= easeOran;
        }

        // Mesafeye göre yavaşlama: hedefe çok yakınken sert dönüşü önle
        if (mesafeyeGoreYavaslat.getValue()) {
            double mesafe = Math.sqrt(PlayerUtility.squaredDistanceFromEyes(hedefKonum));
            float esik = yavaslamaMesafesi.getValue();
            if (mesafe < esik) {
                float yakinlikOrani = 1f - (float)(mesafe / esik); // 0 (uzak) -> 1 (çok yakın)
                float kesinti = yakinlikOrani * yavaslamaGucu.getValue();
                hiz *= (1f - kesinti);
            }
        }

        // Intercept: kaçan hedefin önüne geç
        Vec3d tahminKonum = hedefKonum;
        if (hedefinOnuneGec.getValue()
                && hedef instanceof LivingEntity le && le.isFallFlying()) {
            double mesafe  = Math.sqrt(PlayerUtility.squaredDistanceFromEyes(hedefKonum));
            double tikSayisi = (mesafe / (hiz * 2.0)) * tahminCarpani.getValue();
            tahminKonum    = hedefKonum.add(hedefHizi.multiply(tikSayisi));
        }

        // Yörünge açısını artır — hız çarpanı ile yavaşlatılmış
        double aciArtisi = yorungeHizi.getValue() * hiz;
        if (bypassModu.is(BypassSeviye.Agir)) {
            // Ağır bypass: açı artışına hafif rastgelelik
            aciArtisi *= (0.75 + RNG.nextDouble() * 0.5);
        }
        yorungeAci += aciArtisi;

        double hedefX = tahminKonum.x + Math.cos(yorungeAci) * yaricap;
        double hedefZ = tahminKonum.z + Math.sin(yorungeAci) * yaricap;
        double hedefY = tahminKonum.y + dikeyOffset.getValue();

        // Bakış açısı hesapla
        double dx = hedefX - mc.player.getX();
        double dy = hedefY - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double dz = hedefZ - mc.player.getZ();
        double yataySMesafe = Math.sqrt(dx * dx + dz * dz);

        float hedefYaw   = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float hedefPitch = (float) -Math.toDegrees(Math.atan2(dy, yataySMesafe));

        // Yumuşatma faktörü
        float yumusatma = donusYumusatma.getValue();
        if (bypassModu.is(BypassSeviye.Agir)) {
            yumusatma *= 0.6f; // Ağır bypass: çok daha yavaş dön
        }

        float mevcutYaw   = mc.player.getYaw();
        float mevcutPitch = mc.player.getPitch();

        float hamYawFarki   = MathHelper.wrapDegrees(hedefYaw   - mevcutYaw)   * yumusatma;
        float hamPitchFarki = MathHelper.wrapDegrees(hedefPitch - mevcutPitch) * yumusatma;

        // Maksimum derece limiti — bypass için kritik
        float limit = maxDerecePerTik.getValue();
        if (bypassModu.is(BypassSeviye.Agir)) {
            limit *= 0.55f; // Ağır bypass: limit daha da düşük
        }
        float yeniYawFarki   = MathHelper.clamp(hamYawFarki,   -limit, limit);
        float yeniPitchFarki = MathHelper.clamp(hamPitchFarki, -limit, limit);

        // Rastgele titreşim
        if (rastgeleRotasyon.getValue()) {
            float g = rastgeleMiktar.getValue();
            yeniYawFarki   += (float)(RNG.nextGaussian() * g);
            yeniPitchFarki += (float)(RNG.nextGaussian() * g * 0.5f);
        }

        mc.player.setYaw(mevcutYaw   + yeniYawFarki);
        mc.player.setPitch(mevcutPitch + yeniPitchFarki);

        // Bırakma penceresi için son bakış yönünü sakla
        sonYaw   = mc.player.getYaw();
        sonPitch = mc.player.getPitch();
    }

    /**
     * Kritik vuruş paketleri.
     */
    private void kritikPaketAt() {
        if (mc.player.isInLava() || mc.player.isSubmergedInWater()) return;
        switch (kritikModu.getValue()) {
            case Paket -> {
                // Minimal Y farkı — çoğu anti-cheat sezmiyor
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY() + 0.000000271875, mc.player.getZ(), false));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(), false));
            }
            case Strict -> {
                // Daha uzun atlama simülasyonu
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY() + 0.062600301692775, mc.player.getZ(), false));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY() + 0.07260029960661, mc.player.getZ(), false));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(), false));
            }
        }
    }

    /**
     * Tek bir roket ateşler.
     * SessizRoket açıksa slot değişimi paket düzeyinde — animasyon yok.
     */
    private void roketAt() {
        SearchInvResult hotbarSonuc = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);
        int roketSlotu = hotbarSonuc.slot();

        if (roketSlotu == -1) {
            if (!otomatikRoketSec.getValue()) return;
            SearchInvResult envSonuc = InventoryUtility.findItemInInventory(Items.FIREWORK_ROCKET);
            if (!envSonuc.found()) return;
            // Hotbar dışı envanter geçişi güvensiz — çık
            return;
        }

        int mevcutSlot  = mc.player.getInventory().selectedSlot;
        boolean degisimVar = mevcutSlot != roketSlotu;

        if (sessizRoket.getValue()) {
            if (degisimVar) sendPacket(new UpdateSelectedSlotC2SPacket(roketSlotu));
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
            if (degisimVar) sendPacket(new UpdateSelectedSlotC2SPacket(mevcutSlot));
        } else {
            if (degisimVar) InventoryUtility.switchTo(roketSlotu);
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
        }
    }

    /**
     * Bir sonraki hareket adımına kadar beklenecek süreyi rastgele hesaplar.
     * Bypass seviyesine göre ölçeklenir.
     */
    private void yeniGecikmeHesapla() {
        int min = gecikmeMin.getValue();
        int max = Math.max(min + 1, gecikmeMax.getValue());

        insansiAraliKMs = min + (long)(RNG.nextFloat() * (max - min));

        if (bypassModu.is(BypassSeviye.Agir)) {
            insansiAraliKMs = (long)(insansiAraliKMs * 1.7);
        }
        hareketTimer.reset();
    }

    // ──────────────────────────────────────────────────────────────────
    //  ENUM'LAR
    // ──────────────────────────────────────────────────────────────────

    public enum KritikMod {
        Paket,   // Çok hafif, çoğu sunucuda geçer
        Strict   // Daha gerçekçi atlama, daha yüksek hasar şansı
    }

    public enum BypassSeviye {
        Yok,    // Ham; hızlı ama dikkat çeker
        Hafif,  // Derece limiti + hafif gecikme — önerilen
        Agir    // Her şey yavaş, çok daha güvenli
    }
}
