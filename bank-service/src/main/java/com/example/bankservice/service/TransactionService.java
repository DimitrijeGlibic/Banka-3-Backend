package com.example.bankservice.service;

import com.example.bankservice.client.EmailServiceClient;
import com.example.bankservice.client.UserServiceClient;
import com.example.bankservice.domain.dto.currencyExchange.CurrencyExchangeDto;
import com.example.bankservice.domain.dto.transaction.CompanyOtcTransactionDto;
import com.example.bankservice.domain.dto.transaction.ConfirmPaymentTransactionDto;
import com.example.bankservice.domain.dto.transaction.CreditTransactionDto;
import com.example.bankservice.domain.dto.transaction.FinishedPaymentTransactionDto;
import com.example.bankservice.domain.dto.transaction.PaymentTransactionActivationDto;
import com.example.bankservice.domain.dto.transaction.PaymentTransactionDto;
import com.example.bankservice.domain.dto.transaction.StartPaymentTransactionDto;
import com.example.bankservice.domain.dto.transaction.StockTransactionDto;
import com.example.bankservice.domain.dto.transaction.UserOtcTransactionDto;
import com.example.bankservice.domain.mapper.TransactionMapper;
import com.example.bankservice.domain.model.Transaction;
import com.example.bankservice.domain.model.accounts.Account;
import com.example.bankservice.domain.model.accounts.CompanyAccount;
import com.example.bankservice.domain.model.accounts.UserAccount;
import com.example.bankservice.domain.model.enums.TransactionStatus;
import com.example.bankservice.domain.model.enums.TransactionType;
import com.example.bankservice.repository.AccountRepository;
import com.example.bankservice.repository.TransactionRepository;
import lombok.AllArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@AllArgsConstructor
public class TransactionService {

    private final UserServiceClient userServiceClient;
    private final EmailServiceClient emailServiceClient;
    private final AccountRepository accountRepository;
    private final TransactionMapper transactionMapper;
    private TransactionRepository transactionRepository;
    private AccountService accountService;

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public StartPaymentTransactionDto startPaymentTransaction(PaymentTransactionDto paymentTransactionDto) {
        Account accountFrom = accountService.extractAccountForAccountNumber(paymentTransactionDto.getAccountFrom());
        Account accountTo = accountService.extractAccountForAccountNumber(paymentTransactionDto.getAccountTo());

        if (!accountService.checkBalance(paymentTransactionDto.getAccountFrom(), paymentTransactionDto.getAmount())) {
            throw new RuntimeException("Insufficient funds");
        }
        
        checkIfAccountsAreTheSame(accountFrom, accountTo);

        Long transactionId = 0L;
        if (accountFrom.getCurrency().getMark().equals(accountTo.getCurrency().getMark())) {
            transactionId = startSameCurrencyPaymentTransaction(paymentTransactionDto, accountFrom, accountTo);
        } else {
            throw new RuntimeException("Different currency transactions are not supported");
        }

        return new StartPaymentTransactionDto(transactionId);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void confirmPaymentTransaction(ConfirmPaymentTransactionDto confirmPaymentTransactionDto) {
        Transaction transaction = transactionRepository.findById(confirmPaymentTransactionDto.getTransactionId())
                .orElseThrow(() -> new RuntimeException("Transaction not found"));

        if (transaction.getTransactionStatus().equals(TransactionStatus.PENDING)) {
            acceptTransaction(transaction);
            Account accountFrom = accountService.extractAccountForAccountNumber(transaction.getAccountFrom());

            accountService.reserveFunds(accountFrom, transaction.getAmount());
        } else {
            throw new RuntimeException("Transaction already completed");
        }
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void startCurrencyExchangeTransaction(CurrencyExchangeDto currencyExchangeDto) {
        Account accountFrom = accountService.extractAccountForAccountNumber(currencyExchangeDto.getAccountFrom());
        Account accountTo = accountService.extractAccountForAccountNumber(currencyExchangeDto.getAccountTo());

        checkIfAccountsAreTheSame(accountFrom, accountTo);
        
        if (!accountService.checkBalance(currencyExchangeDto.getAccountFrom(), currencyExchangeDto.getAmount())) {
            throw new RuntimeException("Insufficient funds");
        }

        if (accountFrom.getCurrency().getMark().equals(accountTo.getCurrency().getMark())) {

        } else {
            throw new RuntimeException("Different currency transactions are not supported");
        }

    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void stockBuyTransaction(StockTransactionDto stockTransactionDto) {
        // ovde umesto da trazi bank account, stavimo proveru da li StokcTransactionDto ima userId ili companyId
        // i na osnovu njihovog id-a i marka nadjemo account

        Account accountFrom = null;
        if (stockTransactionDto.getEmployeeId() != null) {
            accountFrom = accountService.findBankAccountForGivenCurrency(stockTransactionDto.getCurrencyMark());
        } else {
            accountFrom = accountService.findAccount(stockTransactionDto);
        }
        Account accountTo = accountService.findExchangeAccountForGivenCurrency(stockTransactionDto.getCurrencyMark());
        
        checkIfAccountsAreTheSame(accountFrom, accountTo);
        
        if (accountFrom.getAvailableBalance().compareTo(BigDecimal.valueOf(stockTransactionDto.getAmount())) < 0) {
            throw new RuntimeException("Insufficient funds");
        }

        Transaction transaction = new Transaction();
        transaction.setAccountFrom(accountFrom.getAccountNumber());
        transaction.setAccountTo(accountTo.getAccountNumber());
        transaction.setAmount(BigDecimal.valueOf(stockTransactionDto.getAmount()));
        transaction.setType(TransactionType.STOCK_TRANSACTION);
        transaction.setTransactionStatus(TransactionStatus.ACCEPTED);
        transaction.setDate(System.currentTimeMillis());

        transactionRepository.save(transaction);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void stockSellTransaction(StockTransactionDto stockTransactionDto) {
        Account accountFrom = accountService.findExchangeAccountForGivenCurrency(stockTransactionDto.getCurrencyMark());
        //ovde umesto da trazi bank account, stavimo proveru da li StokcTransactionDto ima userId ili companyId
        // i na osnovu njihovog id-a i marka nadjemo account

        Account accountTo = null;
        if(stockTransactionDto.getEmployeeId() != null){
            accountTo = accountService.findBankAccountForGivenCurrency(stockTransactionDto.getCurrencyMark());
        } else {
            accountTo = accountService.findAccount(stockTransactionDto);
        }
        
        checkIfAccountsAreTheSame(accountFrom, accountTo);

        if (accountFrom.getAvailableBalance().compareTo(BigDecimal.valueOf(stockTransactionDto.getAmount())) < 0) {
            throw new RuntimeException("Insufficient funds");
        }

        if(stockTransactionDto.getTax() > 0.0) {
            Account bankAccount = accountService.findBankAccountForGivenCurrency(stockTransactionDto.getCurrencyMark());
            if(!accountTo.getAccountNumber().equals(bankAccount.getAccountNumber()))
                startPayTax(accountTo, bankAccount, stockTransactionDto.getTax());
        }

        Transaction transaction = new Transaction();
        transaction.setAccountFrom(accountFrom.getAccountNumber());
        transaction.setAccountTo(accountTo.getAccountNumber());
        transaction.setAmount(BigDecimal.valueOf(stockTransactionDto.getAmount()));
        transaction.setType(TransactionType.STOCK_TRANSACTION);
        transaction.setTransactionStatus(TransactionStatus.ACCEPTED);
        transaction.setDate(System.currentTimeMillis());

        transactionRepository.save(transaction);
    }

    public List<CreditTransactionDto> getAllCreditTransactions() {
        List<Transaction> transactions = transactionRepository.findAllByType(TransactionType.CREDIT_APPROVE_TRANSACTION)
                .orElseThrow(() -> new RuntimeException("Transactions not found"));

        return transactions.stream().map(transactionMapper::transactionToCreditTransactionDto).toList();
    }

    public List<FinishedPaymentTransactionDto> getAllPaymentTransactions(String accountNumber) {
//        List<Transaction> transactions =
//                transactionRepository.findByAccountFromOrAccountToAndType(accountNumber,
//                                accountNumber, TransactionType.PAYMENT_TRANSACTION)
//                .orElseThrow(() -> new RuntimeException("Transactions not found"));

        List<Transaction> transactions =
                transactionRepository.findByAccountFromOrAccountTo(accountNumber,
                                accountNumber)
                        .orElseThrow(() -> new RuntimeException("Transactions not found"));

        Collections.sort(transactions, new Comparator<Transaction>() {
            @Override
            public int compare(Transaction t1, Transaction t2) {
                return t2.getDate().compareTo(t1.getDate());
            }
        });

        return transactions.stream().filter(transaction -> transaction.getTransactionStatus() == TransactionStatus.FINISHED).map(transactionMapper::transactionToFinishedPaymentTransactionDto).toList();
    }
    
    public void otcUserTransaction(UserOtcTransactionDto userOtcTransactionDto) {
        Account accountFrom = accountService.findUserAccountForIdAndCurrency(
                userOtcTransactionDto.getUserFromId(), "RSD");
        Account accountTo = accountService.findUserAccountForIdAndCurrency(
                userOtcTransactionDto.getUserToId(), "RSD");
        
        checkIfAccountsAreTheSame(accountFrom, accountTo);

        if(userOtcTransactionDto.getTax() > 0.0) {
            Account bankAccount = accountService.findBankAccountForGivenCurrency("RSD");
            startPayTax(accountTo, bankAccount, userOtcTransactionDto.getTax());
        }

        startOTCTransaction(accountFrom, accountTo, userOtcTransactionDto.getAmount());
    }
    
    public void otcCompanyTransaction(CompanyOtcTransactionDto companyOtcTransactionDto) {
        Account accountFrom = accountService.findCompanyAccountForIdAndCurrency(
                companyOtcTransactionDto.getCompanyFromId(), "RSD");
        Account accountTo = accountService.findCompanyAccountForIdAndCurrency(
                companyOtcTransactionDto.getCompanyToId(), "RSD");
        
        checkIfAccountsAreTheSame(accountFrom, accountTo);
        //todo payTax(accTO, bankACC, tax)
        if(companyOtcTransactionDto.getTax() > 0.0) {
            Account bankAccount = accountService.findBankAccountForGivenCurrency("RSD");
            if(!accountTo.getAccountNumber().equals(bankAccount.getAccountNumber()))
                startPayTax(accountTo, bankAccount, companyOtcTransactionDto.getTax());
        }

        startOTCTransaction(accountFrom, accountTo, companyOtcTransactionDto.getAmount());
    }

    public void otcBank4Transaction(CompanyOtcTransactionDto companyOtcTransactionDto){
        Account accountFrom = accountService.findCompanyAccountForIdAndCurrency(
                companyOtcTransactionDto.getCompanyFromId(), "RSD");
        Account accountTo = accountService.findCompanyAccountForIdAndCurrency(
                companyOtcTransactionDto.getCompanyToId(), "RSD");

        checkIfAccountsAreTheSame(accountFrom, accountTo);

        startOTCTransaction(accountFrom, accountTo, companyOtcTransactionDto.getAmount());
    }

    private Long startSameCurrencyPaymentTransaction(PaymentTransactionDto paymentTransactionDto,
                                                     Account accountFrom,
                                                     Account accountTo) {
        Transaction transaction = transactionMapper.paymentTransactionDtoToTransaction(paymentTransactionDto);
        transaction.setDate(System.currentTimeMillis());
        transaction.setTransactionStatus(TransactionStatus.PENDING);
        transaction.setType(TransactionType.PAYMENT_TRANSACTION);
        transaction = transactionRepository.save(transaction);

        String email = (accountFrom instanceof UserAccount) ?
                userServiceClient.getEmailByUserId(String.valueOf(((UserAccount) accountFrom).getUserId())).getEmail() :
                userServiceClient.getEmailByCompanyId(String.valueOf(((CompanyAccount) accountFrom).getCompanyId()));

        emailServiceClient.sendTransactionActivationEmailToEmailService(new PaymentTransactionActivationDto(email,
                transaction.getTransactionId()));

        return transaction.getTransactionId();
    }

    private void startPayTax(Account accountFrom, Account accountTo, Double tax) {
        Transaction transaction = new Transaction();
        transaction.setAccountFrom(accountFrom.getAccountNumber());
        transaction.setAccountTo(accountTo.getAccountNumber());
        transaction.setAmount(BigDecimal.valueOf(tax));
        transaction.setType(TransactionType.PAY_TAX_TRANSACTION);
        transaction.setTransactionStatus(TransactionStatus.ACCEPTED);
        transaction.setDate(System.currentTimeMillis());
        transactionRepository.save(transaction);
    }
    
    private void startOTCTransaction(Account accountFrom, Account accountTo, Double amount) {
        Transaction transaction = new Transaction();
        transaction.setAccountFrom(accountFrom.getAccountNumber());
        transaction.setAccountTo(accountTo.getAccountNumber());
        transaction.setAmount(BigDecimal.valueOf(amount));
        transaction.setType(TransactionType.OTC_TRANSACTION);
        transaction.setTransactionStatus(TransactionStatus.ACCEPTED);
        transaction.setDate(System.currentTimeMillis());
        transactionRepository.save(transaction);
    }

    @Scheduled(fixedRate = 30000) // Postavljanje cron izraza da se metoda izvrsava svakih 5 minuta
    public void processTransactions() {

        Optional<List<Transaction>> optionalTransactions = transactionRepository.findAllByTransactionStatus(TransactionStatus.ACCEPTED);
        List<Transaction> transactions;
        if (!optionalTransactions.isPresent()) return;
        transactions = optionalTransactions.get();
        for (Transaction transaction : transactions) {
            if (transaction.getType().equals(TransactionType.CREDIT_APPROVE_TRANSACTION)) {
                finishCreditTransaction(transaction);
            } else if (transaction.getType().equals(TransactionType.PAYMENT_TRANSACTION)) {
                finishTransaction(transaction);
            } else if (transaction.getType().equals(TransactionType.STOCK_TRANSACTION)) {
                finishStockTransaction(transaction);
            } else if (transaction.getType().equals(TransactionType.OTC_TRANSACTION)) {
                finishOTCTransaction(transaction);
            }else if(transaction.getType().equals(TransactionType.PAY_TAX_TRANSACTION)) {
                finishTaxTransaction(transaction);
            }
        }
    }

    private void finishTaxTransaction(Transaction transaction) {
        Account accountFrom = accountRepository.findByAccountNumber(transaction.getAccountFrom())
                .orElseThrow(() -> new RuntimeException("Account not found"));
        Account accountTo = accountRepository.findByAccountNumber(transaction.getAccountTo())
                .orElseThrow(() -> new RuntimeException("Account not found"));

        accountService.transferOtcFunds(accountFrom, accountTo, transaction.getAmount());
        transaction.setTransactionStatus(TransactionStatus.FINISHED);
        transactionRepository.save(transaction);
    }

    private void finishTransaction(Transaction transaction) {
        Account accountFrom = accountRepository.findByAccountNumber(transaction.getAccountFrom())
                .orElseThrow(() -> new RuntimeException("Account not found"));
        Account accountTo = accountRepository.findByAccountNumber(transaction.getAccountTo())
                .orElseThrow(() -> new RuntimeException("Account not found"));

        accountService.transferFunds(accountFrom, accountTo, transaction.getAmount());
        transaction.setTransactionStatus(TransactionStatus.FINISHED);
        transactionRepository.save(transaction);
    }

    private void finishCreditTransaction(Transaction transaction) {
        Account accountFrom = accountRepository.findByAccountNumber(transaction.getAccountFrom())
                .orElseThrow(() -> new RuntimeException("Account not found"));
        Account accountTo = accountRepository.findByAccountNumber(transaction.getAccountTo())
                .orElseThrow(() -> new RuntimeException("Account not found"));

        accountService.transferCreditFunds(accountFrom, accountTo, transaction.getAmount());
        transaction.setTransactionStatus(TransactionStatus.FINISHED);
        transactionRepository.save(transaction);
    }

    private void finishStockTransaction(Transaction transaction) {
        Account accountFrom = accountRepository.findByAccountNumber(transaction.getAccountFrom())
                .orElseThrow(() -> new RuntimeException("Account not found"));
        Account accountTo = accountRepository.findByAccountNumber(transaction.getAccountTo())
                .orElseThrow(() -> new RuntimeException("Account not found"));

        accountService.transferStockFunds(accountFrom, accountTo, transaction.getAmount());
        transaction.setTransactionStatus(TransactionStatus.FINISHED);
        transactionRepository.save(transaction);
    }
    
    private void finishOTCTransaction(Transaction transaction) {
        Account accountFrom = accountRepository.findByAccountNumber(transaction.getAccountFrom())
                .orElseThrow(() -> new RuntimeException("Account not found"));
        Account accountTo = accountRepository.findByAccountNumber(transaction.getAccountTo())
                .orElseThrow(() -> new RuntimeException("Account not found"));

        accountService.transferOtcFunds(accountFrom, accountTo, transaction.getAmount());
        transaction.setTransactionStatus(TransactionStatus.FINISHED);
        transactionRepository.save(transaction);
    }

    private void acceptTransaction(Transaction transaction) {
        transaction.setTransactionStatus(TransactionStatus.ACCEPTED);
        transactionRepository.save(transaction);
    }
    
    private void checkIfAccountsAreTheSame(Account accountFrom, Account accountTo) {
        if (accountFrom.getAccountNumber().equals(accountTo.getAccountNumber())) {
            throw new RuntimeException("Accounts are the same");
        }
    }
}
