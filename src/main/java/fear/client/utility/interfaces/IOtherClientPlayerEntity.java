package fear.client.utility.interfaces;

import fear.client.features.modules.combat.Aura;

public interface IOtherClientPlayerEntity {
    void resolve(Aura.Resolver mode);

    void releaseResolver();
}
