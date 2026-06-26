package fear.client.utility.interfaces;

import fear.client.features.modules.combat.Aura;

import java.util.List;

public interface IEntityLiving {
    double getPrevServerX();

    double getPrevServerY();

    double getPrevServerZ();

    List<Aura.Position> getPositionHistory();
}
