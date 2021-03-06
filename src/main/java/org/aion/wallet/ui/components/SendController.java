package org.aion.wallet.ui.components;

import com.google.common.eventbus.Subscribe;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import org.aion.api.impl.internal.Message;
import org.aion.api.log.LogEnum;
import org.aion.base.util.TypeConverter;
import org.aion.wallet.connector.BlockchainConnector;
import org.aion.wallet.connector.dto.SendTransactionDTO;
import org.aion.wallet.connector.dto.TransactionResponseDTO;
import org.aion.wallet.console.ConsoleManager;
import org.aion.wallet.dto.AccountDTO;
import org.aion.wallet.events.*;
import org.aion.wallet.exception.ValidationException;
import org.aion.wallet.log.WalletLoggerFactory;
import org.aion.wallet.ui.components.partials.TransactionResubmissionDialog;
import org.aion.wallet.util.*;
import org.slf4j.Logger;

import java.math.BigInteger;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

public class SendController extends AbstractController {

    private static final Logger log = WalletLoggerFactory.getLogger(LogEnum.WLT.name());

    private static final String PENDING_MESSAGE = "Sending transaction...";

    private static final String SUCCESS_MESSAGE = "Transaction finished";

    private static final Tooltip NRG_LIMIT_TOOLTIP = new Tooltip("NRG limit");

    private static final Tooltip NRG_PRICE_TOOLTIP = new Tooltip("NRG price");

    private final BlockchainConnector blockchainConnector = BlockchainConnector.getInstance();

    @FXML
    private PasswordField passwordInput;
    @FXML
    private TextField toInput;
    @FXML
    private TextField nrgInput;
    @FXML
    private TextField nrgPriceInput;
    @FXML
    private TextField valueInput;
    @FXML
    private Label txStatusLabel;
    @FXML
    private TextArea accountAddress;
    @FXML
    private TextField accountBalance;
    @FXML
    private Button sendButton;
    @FXML
    private Label timedoutTransactionsLabel;

    private AccountDTO account;

    private boolean connected;

    private final TransactionResubmissionDialog transactionResubmissionDialog = new TransactionResubmissionDialog();

    private SendTransactionDTO transactionToResubmit;

    @Override
    protected void registerEventBusConsumer() {
        super.registerEventBusConsumer();
        EventBusFactory.getBus(HeaderPaneButtonEvent.ID).register(this);
        EventBusFactory.getBus(AccountEvent.ID).register(this);
        EventBusFactory.getBus(TransactionEvent.ID).register(this);
    }

    @Override
    protected void internalInit(final URL location, final ResourceBundle resources) {
        nrgInput.setTooltip(NRG_LIMIT_TOOLTIP);
        nrgPriceInput.setTooltip(NRG_PRICE_TOOLTIP);
        setDefaults();
        if (!ConfigUtils.isEmbedded()) {
            passwordInput.setVisible(false);
            passwordInput.setManaged(false);
        }

        toInput.textProperty().addListener(event -> transactionToResubmit = null);

        nrgInput.textProperty().addListener(event -> transactionToResubmit = null);

        nrgPriceInput.textProperty().addListener(event -> transactionToResubmit = null);

        valueInput.textProperty().addListener(event -> transactionToResubmit = null);
    }

    @Override
    protected void refreshView(final RefreshEvent event) {
        switch (event.getType()) {
            case CONNECTED:
                connected = true;
                if (account != null) {
                    sendButton.setDisable(false);
                }
                break;
            case DISCONNECTED:
                connected = false;
                sendButton.setDisable(true);
                break;
            case TRANSACTION_FINISHED:
                setDefaults();
                refreshAccountBalance();
                break;
            default:
        }
        setTimedoutTransactionsLabelText();
    }

    @FXML
    private void addressPressed(final KeyEvent keyEvent) {
        if (account != null) {
            switch (keyEvent.getCode()) {
                case TAB:
                    try {
                        checkAddress();
                        displayStatus("", false);
                    } catch (ValidationException e) {
                        displayStatus(e.getMessage(), true);
                    }
                    break;
            }
        }
    }

    @FXML
    private void nrgPressed(final KeyEvent keyEvent) {
        if (account != null) {
            switch (keyEvent.getCode()) {
                case TAB:
                    try {
                        getNrg();
                        displayStatus("", false);
                    } catch (ValidationException e) {
                        displayStatus(e.getMessage(), true);
                    }
                    break;
            }
        }
    }

    @FXML
    private void nrgPricePressed(final KeyEvent keyEvent) {
        if (account != null) {
            switch (keyEvent.getCode()) {
                case TAB:
                    try {
                        getNrgPrice();
                        displayStatus("", false);
                    } catch (ValidationException e) {
                        displayStatus(e.getMessage(), true);
                    }
                    break;
            }
        }
    }

    @FXML
    private void valuePressed(final KeyEvent keyEvent) {
        if (account != null) {
            switch (keyEvent.getCode()) {
                case ENTER:
                    sendAion();
                    break;
                case TAB:
                    try {
                        getValue();
                        displayStatus("", false);
                    } catch (ValidationException e) {
                        displayStatus(e.getMessage(), true);
                    }
                    break;
            }
        }
    }

    @FXML
    private void sendPressed(final KeyEvent keyEvent) {
        if (account != null) {
            switch (keyEvent.getCode()) {
                case ENTER:
                    sendAion();
                    break;
            }
        }
    }

    public void sendAion() {
        if (account == null) {
            return;
        }
        final SendTransactionDTO dto;
        try {
            if(transactionToResubmit != null) {
                dto = transactionToResubmit;
            }
            else {
                dto = mapFormData();
            }
        } catch (ValidationException e) {
            log.error(e.getMessage(), e);
            displayStatus(e.getMessage(), true);
            return;
        }
        displayStatus(PENDING_MESSAGE, false);

        final Task<TransactionResponseDTO> sendTransactionTask = getApiTask(this::sendTransaction, dto);

        runApiTask(
                sendTransactionTask,
                evt -> handleTransactionFinished(sendTransactionTask.getValue()),
                getErrorEvent(t -> Optional.ofNullable(t.getCause()).ifPresent(cause -> displayStatus(cause.getMessage(), true)), sendTransactionTask),
                getEmptyEvent()
        );
    }

    public void onTimedOutTransactionsClick(final MouseEvent mouseEvent) {
        transactionResubmissionDialog.open(mouseEvent);
    }

    private void handleTransactionFinished(final TransactionResponseDTO response) {
        setTimedoutTransactionsLabelText();
        final String error = response.getError();
        if (error != null) {
            final String failReason;
            final int responseStatus = response.getStatus();
            if (Message.Retcode.r_tx_Dropped_VALUE == responseStatus) {
                failReason = String.format("dropped: %s", error);
            } else {
                failReason = "timeout";
            }
            final String errorMessage = "Transaction " + failReason;
            ConsoleManager.addLog(errorMessage, ConsoleManager.LogType.TRANSACTION, ConsoleManager.LogLevel.WARNING);
            SendController.log.error("{}: {}", errorMessage, response);
            displayStatus(errorMessage, false);
        } else {
            log.info("{}: {}", SUCCESS_MESSAGE, response);
            ConsoleManager.addLog("Transaction sent", ConsoleManager.LogType.TRANSACTION, ConsoleManager.LogLevel.WARNING);
            displayStatus(SUCCESS_MESSAGE, false);
            EventPublisher.fireTransactionFinished();
        }
    }

    private void displayStatus(final String message, final boolean isError) {
        final ConsoleManager.LogLevel logLevel;
        if (isError) {
            txStatusLabel.getStyleClass().add(ERROR_STYLE);
            logLevel = ConsoleManager.LogLevel.ERROR;

        } else {
            txStatusLabel.getStyleClass().removeAll(ERROR_STYLE);
            logLevel = ConsoleManager.LogLevel.INFO;
        }
        txStatusLabel.setText(message);
        ConsoleManager.addLog(message, ConsoleManager.LogType.TRANSACTION, logLevel);
    }

    private TransactionResponseDTO sendTransaction(final SendTransactionDTO sendTransactionDTO) {
        try {
            return blockchainConnector.sendTransaction(sendTransactionDTO);
        } catch (ValidationException e) {
            throw new RuntimeException(e);
        }
    }

    private void setDefaults() {
        nrgInput.setText(AionConstants.DEFAULT_NRG);
        nrgPriceInput.setText(AionConstants.DEFAULT_NRG_PRICE.toString());

        toInput.setText("");
        valueInput.setText("");
        passwordInput.setText("");

        setTimedoutTransactionsLabelText();
    }

    private void setTimedoutTransactionsLabelText() {
        if(account != null) {
            final List<SendTransactionDTO> timedoutTransactions = blockchainConnector.getAccountManager().getTimedOutTransactions(account.getPublicAddress());
            if(!timedoutTransactions.isEmpty()) {
                timedoutTransactionsLabel.setVisible(true);
                timedoutTransactionsLabel.getStyleClass().add("warning-link-style");
                timedoutTransactionsLabel.setText("You have transactions that require your attention!");
            }
        } else {
            timedoutTransactionsLabel.setVisible(false);
        }
    }

    @Subscribe
    private void handleAccountEvent(final AccountEvent event) {
        final AccountDTO account = event.getPayload();
        if (AccountEvent.Type.CHANGED.equals(event.getType())) {
            connected = blockchainConnector.isConnected();
            if (account.isActive()) {
                this.account = account;
                sendButton.setDisable(!connected);
                accountAddress.setText(this.account.getPublicAddress());
                accountBalance.setVisible(true);
                setAccountBalanceText();
            }
        } else if (AccountEvent.Type.LOCKED.equals(event.getType())) {
            if (account.equals(this.account)) {
                this.account = null;
                sendButton.setDisable(true);
                accountAddress.setText("");
                accountBalance.setVisible(false);
                setDefaults();
                txStatusLabel.setText("");
            }
        }
    }

    @Subscribe
    private void handleHeaderPaneButtonEvent(final HeaderPaneButtonEvent event) {
        if (event.getType().equals(HeaderPaneButtonEvent.Type.SEND)) {
            refreshAccountBalance();
        }
    }

    @Subscribe
    private void handleTransactionResubmitEvent(final TransactionEvent event) {
        SendTransactionDTO sendTransaction = event.getTransaction();
        sendTransaction.setNrgPrice(BigInteger.valueOf(sendTransaction.getNrgPrice() * 2));
        toInput.setText(sendTransaction.getTo());
        nrgInput.setText(sendTransaction.getNrg().toString());
        nrgPriceInput.setText(String.valueOf(sendTransaction.getNrgPrice()));
        valueInput.setText(BalanceUtils.formatBalance(sendTransaction.getValue()));
        txStatusLabel.setText("");
        timedoutTransactionsLabel.setVisible(false);
        transactionToResubmit = sendTransaction;
    }

    private void setAccountBalanceText() {
        accountBalance.setText(account.getFormattedBalance() + " " + AionConstants.CCY);
        UIUtils.setWidth(accountBalance);

    }

    private void refreshAccountBalance() {
        if (account == null) {
            return;
        }
        final Task<BigInteger> getBalanceTask = getApiTask(blockchainConnector::getBalance, account.getPublicAddress());
        runApiTask(
                getBalanceTask,
                evt -> {
                    account.setBalance(getBalanceTask.getValue());
                    setAccountBalanceText();
                },
                getErrorEvent(t -> {}, getBalanceTask),
                getEmptyEvent()
        );
    }

    private SendTransactionDTO mapFormData() throws ValidationException {

        checkAddress();

        final long nrg = getNrg();

        final BigInteger nrgPrice = getNrgPrice();

        final BigInteger value = getValue();

        return new SendTransactionDTO(account, toInput.getText(), nrg, nrgPrice, value);
    }

    private void checkAddress() throws ValidationException {
        if (!AddressUtils.isValid(toInput.getText())) {
            throw new ValidationException("Address is not a valid AION address!");
        }
    }

    private long getNrg() throws ValidationException {
        final long nrg;
        try {
            nrg = TypeConverter.StringNumberAsBigInt(nrgInput.getText()).longValue();
            if (nrg <= 0) {
                throw new ValidationException("Nrg must be greater than 0!");
            }
        } catch (NumberFormatException e) {
            throw new ValidationException("Nrg must be a valid number!");
        }
        return nrg;
    }

    private BigInteger getNrgPrice() throws ValidationException {
        final BigInteger nrgPrice;
        try {
            nrgPrice = TypeConverter.StringNumberAsBigInt(nrgPriceInput.getText());
            if (nrgPrice.compareTo(AionConstants.DEFAULT_NRG_PRICE) < 0) {
                throw new ValidationException(String.format("Nrg price must be greater than %s!", AionConstants.DEFAULT_NRG_PRICE));
            }
        } catch (NumberFormatException e) {
            throw new ValidationException("Nrg price must be a valid number!");
        }
        return nrgPrice;
    }

    private BigInteger getValue() throws ValidationException {
        final BigInteger value;
        try {
            value = BalanceUtils.extractBalance(valueInput.getText());
            if (value.compareTo(BigInteger.ZERO) <= 0) {
                throw new ValidationException("Amount must be greater than 0");
            }
        } catch (NumberFormatException e) {
            throw new ValidationException("Amount must be a number");
        }
        return value;
    }
}
