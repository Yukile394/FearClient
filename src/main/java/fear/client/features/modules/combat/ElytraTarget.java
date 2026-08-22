package fear.client.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.SequencedPacketCreator;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import fear.client.events.impl.EventAttack;
import fear.client.events.impl.EventPostSync;
import fear.client.features.modules.Module;
import fear.client.setting.Setting;
import fear.client.utility.Timer;
import fear.client.utility.player.InventoryUtility;
import fear.client.utility.player.PlayerUtility;
import fear.client.utility.player.SearchInvResult;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ElytraTarget extends Module {

    // ══════════════════════════════════════════════════════
    //  FİŞEK AYARLARI
    // ══════════════════════════════════════════════════════

    /** Elytra uçuşunu hızlandırmak için fişek kullan */
    private final Setting<Boolean> rocketBoost =
            new Setting<>("FisekBoost", true);

    /**
     * En kısa fişek gecikmesi (ms).
     * Hedef uzaklaştıkça bu değere yaklaşır — daha hızlı atar ve yetişir.
     */
    private final Setting<Integer> minRocketDelay =
            new Setting<>("MinFisekGecikmesi", 180, 50, 1000,
                    v -> rocketBoost.getValue());

    /**
     * En uzun fişek gecikmesi (ms).
     * Hedef yakına gelince bu değere yaklaşır — yavaşlar, anti-cheat için güvenli.
     */
    private final Setting<Integer> maxRocketDelay =
            new Setting<>("MaxFisekGecikmesi", 750, 100, 3000,
                    v -> rocketBoost.getValue());

    /**
     * Mesafeye göre fişek gecikmeyi otomatik ayarla.
     * Açık: uzakta hızlı atar, yakında yavaşlar.
     * Kapalı: sabit MinFisekGecikmesi kullanır.
     */
    private final Setting<Boolean> adaptiveDelay =
            new Setting<>("AdaptifGecikme", true,
                    v -> rocketBoost.getValue());

    /** Slot geçişini sunucuya sessizce gönder (görünür tutma) */
    private final Setting<Boolean> silentRockets =
            new Setting<>("SessizFisek", true,
                    v -> rocketBoost.getValue());

    /** Hotbar'da fişek yoksa envanterden otomatik al */
    private final Setting<Boolean> autoSwitchRocket =
            new Setting<>("OtoFisekSec", true,
                    v -> rocketBoost.getValue());

    /** Hedef menzil dışında bile fişek kullanmaya devam et */
    private final Setting<Boolean> alwaysBoost =
            new Setting<>("HepBoost", false,
                    v -> rocketBoost.getValue());

    // ══════════════════════════════════════════════════════
    //  YAKINDA KILIÇ
    // ══════════════════════════════════════════════════════

    /** Hedef bu mesafenin içine girince fişek yerine kılıçla vur */
    private final Setting<Boolean> meleeSwitch =
            new Setting<>("YakinDovus", true);

    /** Kılıç moduna geçiş mesafesi (blok) */
    private final Setting<Float> meleeRange =
            new Setting<>("KilicMesafesi", 5.0f, 1.5f, 15.0f,
                    v -> meleeSwitch.getValue());

    /** Kılıç modunda gerçekten vur (kapalıysa sadece fişeği durdurur) */
    private final Setting<Boolean> meleeAttack =
            new Setting<>("YakinVurus", true,
                    v -> meleeSwitch.getValue());

    /** Yakın vuruştan önce kritik paket gönder */
    private final Setting<Boolean> meleeCrit =
            new Setting<>("YakinKritik", true,
                    v -> meleeSwitch.getValue() && meleeAttack.getValue());

    /** Hotbar'daki en keskin kılıcı otomatik seç */
    private final Setting<Boolean> autoSharpestSword =
            new Setting<>("OtoKilic", true);

    // ══════════════════════════════════════════════════════
    //  HASAR GÖSTERGESİ
    // ══════════════════════════════════════════════════════

    /** Vurduğunda ekranda hasar ve kritik bilgisini göster */
    private final Setting<Boolean> damageDisplay =
            new Setting<>("HasarGostergesi", true);

    /** Vuruş kritik mi olduğunu ✦ simgesiyle göster */
    private final Setting<Boolean> showCritInfo =
            new Setting<>("KritikGoster", true,
                    v -> damageDisplay.getValue());

    /** Vuruştan sonra hedefin kalan canını göster */
    private final Setting<Boolean> showTargetHealth =
            new Setting<>("HedefCan", true,
                    v -> damageDisplay.getValue());

    /** Hasar yazısı ekranda kaç ms kalsın */
    private final Setting<Integer> damageDisplayTime =
            new Setting<>("HasarSuresi", 2000, 500, 5000,
                    v -> damageDisplay.getValue());

    // ══════════════════════════════════════════════════════
    //  HEDEF AYARLARI
    // ══════════════════════════════════════════════════════

    /** Hedefin en fazla bu mesafede olması gerekir (blok) */
    private final Setting<Float> targetRange =
            new Setting<>("HedefMenzil", 64f, 5f, 128f);

    /** Sadece elytra ile uçarken çalış */
    private final Setting<Boolean> onlyWhenFlying =
            new Setting<>("SadeceUcarken", true);

    /** Hedefi yörüngede takip et */
    private final Setting<Boolean> followTarget =
            new Setting<>("HedefiTakipEt", true);

    /** Takip hızı çarpanı */
    private final Setting<Float> followSpeed =
            new Setting<>("TakipHizi", 0.8f, 0.1f, 3.0f,
                    v -> followTarget.getValue());

    /** Hedefin etrafında dönerken tutulacak yarıçap (blok) */
    private final Setting<Float> orbitRadius =
            new Setting<>("YorungeMesafesi", 4.0f, 1.0f, 15.0f,
                    v -> followTarget.getValue());

    /** Hedefin önüne geçmeye çalış (interceptor modu) */
    private final Setting<Boolean> interceptTarget =
            new Setting<>("HedefOnuneGec", true,
                    v -> followTarget.getValue());

    // ══════════════════════════════════════════════════════
    //  KRİTİK VURUŞ (fişek modu)
    // ══════════════════════════════════════════════════════

    /** Fişek fırlatırken kritik pozisyon paketi gönder */
    private final Setting<Boolean> autoCrit =
            new Setting<>("OtoKritik", true);

    /** Packet: tek paket | Strict: gerçekçi atlama simülasyonu (daha iyi bypass) */
    private final Setting<CritMode> critMode =
            new Setting<>("KritikModu", CritMode.Strict,
                    v -> autoCrit.getValue());

    // ══════════════════════════════════════════════════════
    //  ANTİ-CHEAT BYPASS
    // ══════════════════════════════════════════════════════

    /**
     * Paket gönderim zamanlamasına eklenen rastgele gecikme (ms).
     * Sıfır = tahmin edilebilir pattern → AC yakalar.
     * 20-50 arası çoğu AC için yeterli.
     */
    private final Setting<Integer> jitterMs =
            new Setting<>("JitterMs", 25, 0, 150);

    // ══════════════════════════════════════════════════════
    //  DAHILI DURUM
    // ══════════════════════════════════════════════════════
    private final Timer rocketTimer = new Timer();
    private final Timer critTimer   = new Timer();
    private final Timer meleeTimer  = new Timer();
    private final Timer damageTimer = new Timer();

    private double orbitAngle       = 0.0;
    private float  lastTargetHealth = -1f;

    // Son 3 vuruş kaydı (ekran göstergesi için)
    private final Deque<DamageEntry> damageLog = new ArrayDeque<>(3);

    public ElytraTarget() {
        super("ElytraTarget", Category.COMBAT);
    }

    // ══════════════════════════════════════════════════════
    //  ANA TİK
    // ══════════════════════════════════════════════════════
    @EventHandler
    public void onPostSync(EventPostSync e) {
        if (mc.player == null || mc.world == null) return;

        Entity target = Aura.target;
        if (target == null) return;

        if (onlyWhenFlying.getValue() && !mc.player.isFallFlying()) return;

        double distSq  = PlayerUtility.squaredDistanceFromEyes(target.getPos());
        float  rangeSq = targetRange.getValue() * targetRange.getValue();
        boolean inRange = distSq <= rangeSq;

        if (!inRange && !alwaysBoost.getValue()) return;

        double dist = Math.sqrt(distSq);

        // ── En keskin kılıcı sessizce seç ─────────────────────────────
        if (autoSharpestSword.getValue()) {
            SearchInvResult sword = InventoryUtility.getHighestSharpnessSwordHotBar();
            if (sword.found() && !sword.isHolding()) {
                sendPacket(new UpdateSelectedSlotC2SPacket(sword.slot()));
            }
        }

        // ── Yakın dövüş: fişek yerine kılıç ──────────────────────────
        boolean inMeleeRange = dist <= meleeRange.getValue();
        if (meleeSwitch.getValue() && inMeleeRange && inRange) {
            if (followTarget.getValue()) orbit(target, dist);
            handleMelee(target);
            return;   // fişek atma
        }

        // ── Yörüngede takip ───────────────────────────────────────────
        if (followTarget.getValue() && inRange) {
            orbit(target, dist);
        }

        // ── Kritik paket (fişek modunda) ──────────────────────────────
        if (autoCrit.getValue() && inRange && critTimer.passedMs(250 + jitter())) {
            sendCritPacket();
            critTimer.reset();
        }

        // ── Adaptif tek fişek ─────────────────────────────────────────
        if (!rocketBoost.getValue()) return;

        long delay = computeRocketDelay(dist);
        if (!rocketTimer.passedMs(delay)) return;

        fireSingleRocket();
        rocketTimer.reset();
    }

    // ══════════════════════════════════════════════════════
    //  HASAR OLAYINI YAKALA
    // ══════════════════════════════════════════════════════
    @EventHandler
    public void onAttack(EventAttack e) {
        if (!damageDisplay.getValue()) return;
        if (e.isPre()) {
            // Pre: canı kaydet
            if (e.getEntity() instanceof LivingEntity le) {
                lastTargetHealth = le.getHealth();
            }
            return;
        }
        // Post: hasarı hesapla
        if (!(e.getEntity() instanceof LivingEntity le)) return;

        float dmg = lastTargetHealth - le.getHealth();
        if (dmg <= 0f) return;

        boolean crit = mc.player.fallDistance > 0
                && !mc.player.isOnGround()
                && !mc.player.isClimbing()
                && !mc.player.isSubmergedInWater()
                || mc.player.isFallFlying();

        if (damageLog.size() >= 3) damageLog.pollFirst();
        damageLog.addLast(new DamageEntry(dmg, crit, le.getHealth()));
        damageTimer.reset();
    }

    // ══════════════════════════════════════════════════════
    //  2D RENDER — hasar göstergesi
    // ══════════════════════════════════════════════════════
    @Override
    public void onRender2D(DrawContext ctx) {
        if (!damageDisplay.getValue()) return;
        if (damageLog.isEmpty()) return;

        // Süre dolduysa temizle
        if (damageTimer.passedMs(damageDisplayTime.getValue())) {
            damageLog.clear();
            return;
        }

        int cx = mc.getWindow().getScaledWidth()  / 2;
        int cy = mc.getWindow().getScaledHeight() / 2;
        int y  = cy + 6;

        int idx = 0;
        for (DamageEntry entry : damageLog) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("§c-%.1f", entry.damage));
            if (showCritInfo.getValue()     && entry.crit) sb.append(" §e✦KRİT");
            if (showTargetHealth.getValue())               sb.append(String.format(" §7❤§c%.1f", entry.hp));

            ctx.drawTextWithShadow(
                    mc.textRenderer,
                    Text.literal(sb.toString()),
                    cx + 8,
                    y + idx * 10,
                    0xFF_FF4444
            );
            idx++;
        }
    }

    // ══════════════════════════════════════════════════════
    //  YAKINDA KILIÇLA VUR
    // ══════════════════════════════════════════════════════
    private void handleMelee(Entity target) {
        if (!meleeAttack.getValue()) return;

        SearchInvResult sword = InventoryUtility.getHighestSharpnessSwordHotBar();
        if (!sword.found()) return;

        int prevSlot = mc.player.getInventory().selectedSlot;

        // Saldırı cooldown: 0.9+ olmalı
        if (mc.player.getAttackCooldownProgress(0.5f) < 0.9f) return;

        // Değişken gecikme — AC bypass
        if (!meleeTimer.passedMs(350 + jitter())) return;

        // Kılıç slotuna geç (sessiz)
        if (!sword.isHolding()) {
            sendPacket(new UpdateSelectedSlotC2SPacket(sword.slot()));
        }

        // Kritik paket
        if (meleeCrit.getValue()) sendCritPacket();

        // Hedef canını kaydet (hasar hesabı için)
        if (target instanceof LivingEntity le) {
            lastTargetHealth = le.getHealth();
        }

        // Vur
        mc.interactionManager.attackEntity(mc.player, target);
        sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        meleeTimer.reset();

        // Önceki slota dön (sessiz)
        if (!sword.isHolding()) {
            sendPacket(new UpdateSelectedSlotC2SPacket(prevSlot));
        }
    }

    // ══════════════════════════════════════════════════════
    //  ADAPTİF GECİKME HESABI
    //  dist → 0    : maxDelay (yakın, yavaş)
    //  dist → range: minDelay (uzak, hızlı — yetişmek için)
    // ══════════════════════════════════════════════════════
    private long computeRocketDelay(double dist) {
        if (!adaptiveDelay.getValue()) {
            return minRocketDelay.getValue() + jitter();
        }
        float t     = (float) MathHelper.clamp(dist / targetRange.getValue(), 0.0, 1.0);
        long  delay = (long) MathHelper.lerp(t,
                (float) maxRocketDelay.getValue(),
                (float) minRocketDelay.getValue());
        return delay + jitter();
    }

    // ══════════════════════════════════════════════════════
    //  TEK FİŞEK AT  (burst yok — her çağrıda 1 fişek)
    // ══════════════════════════════════════════════════════
    private void fireSingleRocket() {
        SearchInvResult rocket = InventoryUtility.findItemInHotBar(Items.FIREWORK_ROCKET);

        if (!rocket.found()) {
            if (!autoSwitchRocket.getValue()) return;
            // Envanterden hotbar'a taşıma client-side mümkün değil burada
            return;
        }

        int prevSlot  = mc.player.getInventory().selectedSlot;
        boolean swap  = !rocket.isHolding();

        if (silentRockets.getValue()) {
            if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(rocket.slot()));
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
            if (swap) sendPacket(new UpdateSelectedSlotC2SPacket(prevSlot));
        } else {
            if (swap) rocket.switchTo();
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, id, mc.player.getYaw(), mc.player.getPitch()));
        }
    }

    // ══════════════════════════════════════════════════════
    //  YÖRÜNGELİ TAKİP
    // ══════════════════════════════════════════════════════
    private void orbit(Entity target, double dist) {
        Vec3d targetPos    = target.getPos();
        Vec3d targetVel    = target.getVelocity();
        float speed        = followSpeed.getValue();
        float radius       = orbitRadius.getValue();

        Vec3d predictedPos = targetPos;
        if (interceptTarget.getValue() && target instanceof LivingEntity le && le.isFallFlying()) {
            double ticks = dist / Math.max(speed * 2.0, 0.1);
            predictedPos = targetPos.add(targetVel.multiply(ticks));
        }

        orbitAngle += 0.04 * speed;

        double tx  = predictedPos.x + Math.cos(orbitAngle) * radius;
        double ty  = predictedPos.y + 2.0;
        double tz  = predictedPos.z + Math.sin(orbitAngle) * radius;
        double dx  = tx - mc.player.getX();
        double dy  = ty - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double dz  = tz - mc.player.getZ();
        double hd  = Math.sqrt(dx * dx + dz * dz);

        float yaw   = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, hd));
        float cy    = mc.player.getYaw();
        float cp    = mc.player.getPitch();

        mc.player.setYaw  (cy + MathHelper.wrapDegrees(yaw   - cy) * speed * 0.3f);
        mc.player.setPitch(cp + MathHelper.wrapDegrees(pitch - cp) * speed * 0.3f);
    }

    // ══════════════════════════════════════════════════════
    //  KRİTİK PAKET GÖNDERİMİ
    // ══════════════════════════════════════════════════════
    private void sendCritPacket() {
        if (mc.player.isInLava() || mc.player.isSubmergedInWater()) return;
        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();

        switch (critMode.getValue()) {
            case Packet -> {
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.0000002718, z, false));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false));
            }
            case Strict -> {
                // Gerçekçi atlama yüksekliği dağılımı — SemiAntiBot/NCP bypass
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.0625, z, false));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.07260029960661, z, false));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.02260029910661, z, false));
                sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false));
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  YARDIMCILAR
    // ══════════════════════════════════════════════════════

    /** AC pattern kırıcı — her pakete küçük rastgele gecikme */
    private long jitter() {
        return (long) (Math.random() * jitterMs.getValue());
    }

    // ══════════════════════════════════════════════════════
    //  İÇ SINIFLAR
    // ══════════════════════════════════════════════════════
    private record DamageEntry(float damage, boolean crit, float hp) {}

    public enum CritMode { Packet, Strict }
            }
            
