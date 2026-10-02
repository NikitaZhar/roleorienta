package com.roleorienta.worker.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.career.BoardDiscoveryHandler;
import com.roleorienta.worker.career.CareerScanHandler;
import com.roleorienta.worker.collect.ReadSourceHandler;
import com.roleorienta.worker.delivery.RunPassHandler;
import com.roleorienta.worker.geo.GeoImportHandler;
import com.roleorienta.worker.intake.RegistryIntakeHandler;
import com.roleorienta.worker.match.MatchVacanciesHandler;
import com.roleorienta.worker.site.SiteScanHandler;
import com.roleorienta.worker.stateportal.StatePortalHandler;
import org.junit.jupiter.api.Test;

/**
 * Выбор очереди по типу задания (стенограмма §46): типы поиска записаны в топологии строками — тест
 * сверяет их с константами обработчиков, чтобы переименование типа не увело задание в чужую очередь.
 */
class RabbitTopologyTests {

    /**
     * Поиск — реестр, сайты, кадровые страницы, обратный путь, портал, справочник — в очередь поиска.
     */
    @Test
    void discoveryTasksGoToDiscoveryQueue() {
        assertThat(RabbitTopology.DISCOVERY_TYPES).containsExactlyInAnyOrder(RegistryIntakeHandler.TYPE,
                SiteScanHandler.TYPE, CareerScanHandler.TYPE, BoardDiscoveryHandler.TYPE, StatePortalHandler.TYPE,
                GeoImportHandler.TYPE);
        assertThat(RabbitTopology.routingKey(StatePortalHandler.TYPE)).isEqualTo(RabbitTopology.DISCOVERY_ROUTING_KEY);
    }

    /**
     * Чтение источников, соответствия и проходы выдачи — в очередь сбора.
     */
    @Test
    void collectionTasksGoToWorkQueue() {
        for (String type : new String[] {ReadSourceHandler.TYPE, MatchVacanciesHandler.TYPE, RunPassHandler.TYPE}) {
            assertThat(RabbitTopology.routingKey(type)).isEqualTo(RabbitTopology.ROUTING_KEY);
        }
    }
}
