package cn.biq.mn.user.account;

import cn.biq.mn.user.base.BaseService;
import cn.biq.mn.user.book.BookRepository;
import cn.biq.mn.user.group.Group;
import cn.biq.mn.user.utils.EnumUtils;
import cn.biq.mn.user.utils.Limitation;
import cn.biq.mn.user.utils.SessionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import cn.biq.mn.base.exception.FailureMessageException;
import cn.biq.mn.base.exception.ItemExistsException;
import cn.biq.mn.user.balanceflow.BalanceFlow;
import cn.biq.mn.user.balanceflow.BalanceFlowMapper;
import cn.biq.mn.user.balanceflow.BalanceFlowRepository;
import cn.biq.mn.user.balanceflow.FlowType;
import cn.biq.mn.user.currency.CurrencyService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional
public class AccountService {

    private final SessionUtil sessionUtil;
    private final AccountRepository accountRepository;
    private final BookRepository bookRepository;
    private final CurrencyService currencyService;
    private final BalanceFlowRepository balanceFlowRepository;
    private final EnumUtils enumUtils;
    private final BaseService baseService;

    public boolean add(AccountAddForm form) {
        Group group = sessionUtil.getCurrentGroup();
        if (accountRepository.countByGroup(group) >= Limitation.account_max_count) {
            throw new FailureMessageException("account.max.count");
        }
        if (accountRepository.existsByGroupAndName(group, form.getName())) {
            throw new ItemExistsException();
        }
        Account account = AccountMapper.toEntity(form);
        if (!StringUtils.hasText(form.getCurrencyCode())) {
            account.setCurrencyCode(group.getDefaultCurrencyCode());
        }
        if (!Objects.equals(group.getDefaultCurrencyCode(), account.getCurrencyCode())) {
            currencyService.checkCode(form.getCurrencyCode());
        }
        account.setGroup(group);
        accountRepository.save(account);
        return true;
    }

    public boolean update(Integer id, AccountUpdateForm form) {
        Group group = sessionUtil.getCurrentGroup();
        Account entity = baseService.findAccountById(id);
        if (!entity.getName().equals(form.getName())) {
            if (StringUtils.hasText(form.getName())) {
                if (accountRepository.existsByGroupAndName(group, form.getName())) {
                    throw new ItemExistsException();
                }
            }
        }
        AccountMapper.updateEntity(form, entity);
        accountRepository.save(entity);
        return true;
    }

    @Transactional(readOnly = true)
    public Page<AccountDetails> query(AccountQueryForm form, Pageable page) {
        Group group = sessionUtil.getCurrentGroup();
        Page<Account> entityPage = accountRepository.findAll(form.buildPredicate(group), page);
        return entityPage.map(account -> {
            var details = AccountMapper.toDetails(account);
            details.setConvertedBalance(currencyService.convert(details.getBalance(), details.getCurrencyCode(), sessionUtil.getCurrentGroup().getDefaultCurrencyCode()));
            details.setTypeName(enumUtils.translateAccountType(details.getType()));
            return details;
        });
    }

    @Transactional(readOnly = true)
    public List<AccountDetails> queryAll(AccountQueryForm form) {
        form.setEnable(true);
        Group group = sessionUtil.getCurrentGroup();
        List<Account> entityList = accountRepository.findAll(form.buildPredicate(group));
        Account keep = baseService.findAccountById(form.getKeep());
        if (keep != null && !entityList.contains(keep)) {
            entityList.add(0, keep);
        }
        return entityList.stream().map(AccountMapper::toDetails).toList();
    }

    @Transactional(readOnly = true)
    public AccountDetails get(Integer id) {
        Account entity = baseService.findAccountById(id);
        var details = AccountMapper.toDetails(entity);
        details.setConvertedBalance(currencyService.convert(details.getBalance(), details.getCurrencyCode(), sessionUtil.getCurrentGroup().getDefaultCurrencyCode()));
        details.setTypeName(enumUtils.translateAccountType(details.getType()));
        return details;
    }

    @Transactional(readOnly = true)
    public BigDecimal[] statistics(AccountQueryForm form) {
        var result = new BigDecimal[3];
        Group group = sessionUtil.getCurrentGroup();
        List<Account> accounts = accountRepository.findAll(form.buildPredicate(group));
        BigDecimal balance = BigDecimal.ZERO;
        for (Account account : accounts) {
            balance = balance.add(currencyService.convert(account.getBalance(), account.getCurrencyCode(), group.getDefaultCurrencyCode()));
        }
        result[0] = balance;
        BigDecimal creditLimit = BigDecimal.ZERO;
        for (Account account : accounts) {
            if (account.getCreditLimit() != null) {
                creditLimit = creditLimit.add(currencyService.convert(account.getCreditLimit(), account.getCurrencyCode(), group.getDefaultCurrencyCode()));
            }
        }
        result[1] = creditLimit;
        result[2] = creditLimit.add(balance);
        return result;
    }

    // 软删除
    public boolean delete(Integer id) {
        Account entity = baseService.findAccountById(id);
        entity.setDeleted(true);
        entity.setEnable(false);
        entity.setInclude(false);
        entity.setCanExpense(false);
        entity.setCanIncome(false);
        entity.setCanTransferFrom(false);
        entity.setCanTransferTo(false);
        return true;
    }

    // 删除恢复
    public boolean recover(Integer id) {
        Account entity = baseService.findAccountById(id);
        entity.setDeleted(false);
        entity.setEnable(true);
        entity.setInclude(true);
        entity.setCanExpense(true);
        entity.setCanIncome(true);
        entity.setCanTransferFrom(true);
        entity.setCanTransferTo(true);
        return true;
    }

    // 彻底删除
    public boolean remove(Integer id) {
        Account entity = baseService.findAccountById(id);
        // 账户有关联账单的无法删除
        if (balanceFlowRepository.existsByAccountOrTo(entity, entity)) {
            throw new FailureMessageException("account.delete.fail.has.flow");
        }
        // 账本默认账户无法删除
        if (bookRepository.existsByDefaultExpenseAccount(entity)) {
            throw new FailureMessageException("account.action.DefaultExpenseAccount");
        }
        if (bookRepository.existsByDefaultIncomeAccount(entity)) {
            throw new FailureMessageException("account.action.DefaultIncomeAccount");
        }
        if (bookRepository.existsByDefaultTransferFromAccount(entity)) {
            throw new FailureMessageException("account.action.DefaultTransferFromAccount");
        }
        if (bookRepository.existsByDefaultTransferToAccount(entity)) {
            throw new FailureMessageException("account.action.DefaultTransferToAccount");
        }
        accountRepository.delete(entity);
        return true;
    }

    public boolean toggle(Integer id) {
        Account entity = baseService.findAccountById(id);
        if (bookRepository.existsByDefaultExpenseAccount(entity)) {
            throw new FailureMessageException("account.action.DefaultExpenseAccount");
        }
        if (bookRepository.existsByDefaultIncomeAccount(entity)) {
            throw new FailureMessageException("account.action.DefaultIncomeAccount");
        }
        if (bookRepository.existsByDefaultTransferFromAccount(entity)) {
            throw new FailureMessageException("account.action.DefaultTransferFromAccount");
        }
        if (bookRepository.existsByDefaultTransferToAccount(entity)) {
            throw new FailureMessageException("account.action.DefaultTransferToAccount");
        }
        entity.setEnable(!entity.getEnable());
        accountRepository.save(entity);
        return true;
    }

    public boolean toggleInclude(Integer id) {
        Account entity = baseService.findAccountById(id);
        entity.setInclude(!entity.getInclude());
        accountRepository.save(entity);
        return true;
    }

    public boolean toggleCanExpense(Integer id) {
        Account entity = baseService.findAccountById(id);
        if (bookRepository.existsByDefaultExpenseAccount(entity)) {
            throw new FailureMessageException("account.action.DefaultExpenseAccount");
        }
        entity.setCanExpense(!entity.getCanExpense());
        accountRepository.save(entity);
        return true;
    }

    public boolean toggleCanIncome(Integer id) {
        Account entity = baseService.findAccountById(id);
        if (bookRepository.existsByDefaultIncomeAccount(entity)) {
            throw new FailureMessageException("account.action.DefaultIncomeAccount");
        }
        entity.setCanIncome(!entity.getCanIncome());
        accountRepository.save(entity);
        return true;
    }

    public boolean toggleCanTransferFrom(Integer id) {
        Account entity = baseService.findAccountById(id);
        if (bookRepository.existsByDefaultTransferFromAccount(entity)) {
            throw new FailureMessageException("account.action.DefaultTransferFromAccount");
        }
        entity.setCanTransferFrom(!entity.getCanTransferFrom());
        accountRepository.save(entity);
        return true;
    }

    public boolean toggleCanTransferTo(Integer id) {
        Account entity = baseService.findAccountById(id);
        if (bookRepository.existsByDefaultTransferToAccount(entity)) {
            throw new FailureMessageException("account.action.DefaultTransferToAccount");
        }
        entity.setCanTransferTo(!entity.getCanTransferTo());
        accountRepository.save(entity);
        return true;
    }

    public boolean adjustBalance(Integer id, AdjustBalanceAddForm form) {
        Group group = sessionUtil.getCurrentGroup();
        Account entity = baseService.findAccountById(id);
        BigDecimal adjustAmount = form.getBalance().subtract(entity.getBalance());
        // 余额没有变化
        if (adjustAmount.signum() == 0) throw new FailureMessageException("account.adjust.balance.same");
        entity.setBalance(form.getBalance());
        accountRepository.save(entity);
        BalanceFlow flow = new BalanceFlow();
        flow.setType(FlowType.ADJUST);
        flow.setGroup(group);
        flow.setCreator(sessionUtil.getCurrentUser());
        flow.setBook(baseService.findBookById(form.getBookId()));
        flow.setAccount(entity);
        flow.setAmount(adjustAmount);
        flow.setTitle(form.getTitle());
        flow.setNotes(form.getNotes());
        flow.setCreateTime(form.getCreateTime());
        flow.setConfirm(true);
        balanceFlowRepository.save(flow);
        return true;
    }

    public boolean updateAdjustBalance(Integer id, AdjustBalanceUpdateForm form) {
        BalanceFlow balanceFlow = baseService.findFlowById(id);
        BalanceFlowMapper.updateEntity(form, balanceFlow);
        if (form.getBookId() != null) {
            balanceFlow.setBook(baseService.findBookById(form.getBookId()));
        }
        balanceFlowRepository.save(balanceFlow);
        return true;
    }

    @Transactional(readOnly = true)
    public BigDecimal[] overview() {
        var result = new BigDecimal[3];
        Group group = sessionUtil.getCurrentGroup();
        List<Account> assetAccounts = getAssets();
        BigDecimal assetBalance = BigDecimal.ZERO;
        for (Account account : assetAccounts) {
            assetBalance = assetBalance.add(currencyService.convert(account.getBalance(), account.getCurrencyCode(), group.getDefaultCurrencyCode()));
        }
        result[0] = assetBalance;

        List<Account> debtAccounts = getDebts();
        BigDecimal debtBalance = BigDecimal.ZERO;
        for (Account account : debtAccounts) {
            debtBalance = debtBalance.add(currencyService.convert(account.getBalance(), account.getCurrencyCode(), group.getDefaultCurrencyCode()));
        }
        result[1] = debtBalance.negate();
        result[2] = result[0].subtract(result[1]);
        return result;
    }

    public List<Account> getAssets() {
        Group group = sessionUtil.getCurrentGroup();
        List<Account> assetAccounts = new ArrayList<>();
        assetAccounts.addAll(accountRepository.findAllByGroupAndTypeAndEnableAndInclude(group, AccountType.CHECKING, true, true));
        assetAccounts.addAll(accountRepository.findAllByGroupAndTypeAndEnableAndInclude(group, AccountType.ASSET, true, true));
        return assetAccounts;
    }

    public List<Account> getDebts() {
        Group group = sessionUtil.getCurrentGroup();
        List<Account> debtAccounts = new ArrayList<>();
        debtAccounts.addAll(accountRepository.findAllByGroupAndTypeAndEnableAndInclude(group, AccountType.CREDIT, true, true));
        debtAccounts.addAll(accountRepository.findAllByGroupAndTypeAndEnableAndInclude(group, AccountType.DEBT, true, true));
        return debtAccounts;
    }

}
