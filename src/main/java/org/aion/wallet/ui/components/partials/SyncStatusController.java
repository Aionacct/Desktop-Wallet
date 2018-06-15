package org.aion.wallet.ui.components.partials;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import org.aion.wallet.connector.BlockchainConnector;
import org.aion.wallet.connector.dto.SyncInfoDTO;
import org.aion.wallet.ui.components.AbstractController;
import org.aion.wallet.events.RefreshEvent;
import org.aion.wallet.util.SyncStatusUtils;

import java.net.URL;
import java.util.EnumSet;
import java.util.ResourceBundle;

public class SyncStatusController extends AbstractController {

    private final BlockchainConnector blockchainConnector = BlockchainConnector.getInstance();

    @FXML
    private Label progressBarLabel;

    @Override
    protected void internalInit(URL location, ResourceBundle resources) {
    }

    @Override
    protected final void refreshView(final RefreshEvent event) {
        if (EnumSet.of(RefreshEvent.Type.TIMER, RefreshEvent.Type.CONNECTED).contains(event.getType())) {
            final Task<SyncInfoDTO> getSyncInfoTask = getApiTask(o -> blockchainConnector.getSyncInfo(), null);
            runApiTask(
                    getSyncInfoTask,
                    evt -> setSyncStatus(getSyncInfoTask.getValue()),
                    getErrorEvent(t -> {}, getSyncInfoTask),
                    getEmptyEvent()
            );
        }
    }

    private void setSyncStatus(final SyncInfoDTO syncInfo) {
        progressBarLabel.setText(getSyncLabelText(syncInfo));
    }

    private String getSyncLabelText(final SyncInfoDTO syncInfo) {
        return SyncStatusUtils.formatSyncStatus(syncInfo);
    }
}
