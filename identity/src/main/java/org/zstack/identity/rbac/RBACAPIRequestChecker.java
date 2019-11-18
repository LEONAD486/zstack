package org.zstack.identity.rbac;

import edu.emory.mathcs.backport.java.util.Arrays;
import org.zstack.core.cloudbus.CloudBusGson;
import org.zstack.header.errorcode.OperationFailureException;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.identity.*;
import org.zstack.header.identity.rbac.PolicyMatcher;
import org.zstack.header.identity.rbac.RBAC;
import org.zstack.header.identity.rbac.RBACEntity;
import org.zstack.header.identity.rbac.SuppressRBACCheck;
import org.zstack.header.message.APIMessage;
import org.zstack.identity.APIRequestChecker;
import org.zstack.identity.rbac.datatype.Entity;
import org.zstack.utils.Utils;
import org.zstack.utils.logging.CLogger;

import java.lang.reflect.Field;
import java.util.*;

import static org.zstack.core.Platform.operr;

public class RBACAPIRequestChecker implements APIRequestChecker {
    protected static final CLogger logger = Utils.getLogger(RBACAPIRequestChecker.class);

    protected RBACEntity rbacEntity;
    protected PolicyMatcher policyMatcher = new PolicyMatcher();

    public boolean bypass(RBACEntity entity) {
        return entity.getApiMessage().getHeaders().containsKey(IdentityByPassCheck.NoRBACCheck.toString());
    }

    @Override
    public void check(RBACEntity entity) {
        rbacEntity = entity;
        if (rbacEntity.getApiMessage().getClass().isAnnotationPresent(SuppressRBACCheck.class)) {
            return;
        }

        check();
    }

    public boolean evalStatement(String as, String msgName) {
        String ap = PolicyUtils.apiNamePatternFromAction(as);
        return policyMatcher.match(ap, msgName);
    }

    protected List<PolicyInventory> getPoliciesForAPI() {
        return RBACManager.getPoliciesByAPI(rbacEntity.getApiMessage());
    }

    /**
     * rule evaluation order:
     * 3. if any user defined policy denies the API, deny
     * 4. if any user defined policy allows the API, allow
     * 5. then deny by default
     */
    protected void check() {
        List<PolicyInventory> polices = getPoliciesForAPI();
        Map<PolicyInventory, List<PolicyStatement>> denyStatements = RBACManager.collectDenyStatements(polices);
        Map<PolicyInventory, List<PolicyStatement>> allowStatements = RBACManager.collectAllowedStatements(polices);

        evalDenyStatements(denyStatements);

        if (evalAllowStatements(allowStatements)) {
            // allowed
            return;
        }

        if (logger.isTraceEnabled()) {
            logger.trace(String.format("[RBAC]operation is denied by default, API:\n%s", jsonMessage()));
        }

        // no polices applied to the operation, deny by default
        throw new OperationFailureException(operr("operation[API:%s] is denied by default, please contact admin to correct it", rbacEntity.getApiMessage().getClass().getName()));
    }

    private String jsonMessage() {
        return CloudBusGson.toLogSafeJson(rbacEntity.getApiMessage());
    }

    protected boolean evalAllowStatements(Map<PolicyInventory, List<PolicyStatement>> policies) {
        AllowActionChecker aac = new AllowActionChecker();
        aac.policies = policies;

        AllowFilterResourceChecker cfc = new AllowFilterResourceChecker();
        cfc.policies = policies;

        return aac.isAllowed() && cfc.isAllowed();
    }

    class AllowActionChecker {
        Map<PolicyInventory, List<PolicyStatement>> policies;

        boolean isAllowed() {
            Set<String> apiNeedToCheck = new HashSet<>();
            apiNeedToCheck.add(rbacEntity.getApiName());
            apiNeedToCheck.addAll(rbacEntity.getAdditionalApisToCheck());

            for (Map.Entry<PolicyInventory, List<PolicyStatement>> e : policies.entrySet()) {
                PolicyInventory policy = e.getKey();

                for (PolicyStatement statement : e.getValue()) {
                    if (!isPrincipalMatched(statement.getPrincipals())) {
                        continue;
                    }

                    for (String as : statement.getActions()) {
                        apiNeedToCheck.removeIf(api -> evalAllowStatement(as, api));

                        if (apiNeedToCheck.isEmpty()) {
                            if (logger.isTraceEnabled()) {
                                logger.trace(String.format("[RBAC] policy[name:%s, uuid:%s]'s statement[%s] allows the API:\n%s", policy.getName(),
                                        policy.getUuid(), as, jsonMessage()));
                            }
                            return true;
                        }
                    }
                }
            }


            return false;
        }
    }

    class AllowFilterResourceChecker {
        Map<PolicyInventory, List<PolicyStatement>> policies;

        boolean isAllowed() {
            Map<String, List<String>> resourceUuidOfAllParamInApiMessage = getResourceUuidOfAllParamInApiMessage();

            if (resourceUuidOfAllParamInApiMessage.isEmpty()) {
                return true;
            }

            Map<String, List<String>> resourceUuidInPolicy = new HashMap<>();
            for (Map.Entry<PolicyInventory, List<PolicyStatement>> e : policies.entrySet()) {
                for (PolicyStatement statement : e.getValue()) {
                    if (!isPrincipalMatched(statement.getPrincipals())) {
                        continue;
                    }

                    if (statement.getTargetResources() == null) {
                        continue;
                    }

                    for (String filter : statement.getTargetResources()) {
                        String[] ss = filter.split(":", 2);
                        String resourceName = ss[0];
                        Set<String> allowedResourceUuids = new HashSet<>();

                        if (ss.length > 1) {
                            allowedResourceUuids.addAll(Arrays.asList(ss[1].split(",")));
                        }

                        resourceUuidInPolicy.putIfAbsent(resourceName, new ArrayList<>());
                        resourceUuidInPolicy.get(resourceName).addAll(allowedResourceUuids);
                    }
                }
            }

            for (Map.Entry<String, List<String>> e : resourceUuidInPolicy.entrySet()) {
                List<String> uuids = resourceUuidOfAllParamInApiMessage.get(e.getKey());
                if (uuids == null || uuids.isEmpty()) {
                    continue;
                }

                uuids.retainAll(e.getValue());

                if (!uuids.isEmpty()) {
                    if (logger.isTraceEnabled()) {
                        logger.debug(String.format("uuid %s are not allowed by resource filter policy", uuids));
                    }

                    return false;
                }
            }

            return true;
        }
    }

    protected boolean evalAllowStatement(String as, String targetApiName) {
        String ap = PolicyUtils.apiNamePatternFromAction(as, true);
        return RBAC.checkAPIPermission(rbacEntity.getApiMessage(), policyMatcher.match(ap, targetApiName));
    }

    protected boolean isPrincipalMatched(List<String> principals) {
        // if not principals specified, means the statement applies for all accounts/users
        // if principals specified, check if they matches current account/user
        if (principals != null && !principals.isEmpty()) {
            for (String s : principals) {
                String[] ss = s.split(":", 2);
                String principal = ss[0];
                String uuidRegex = ss[1];

                if (rbacEntity.getApiMessage().getSession().isAccountSession() && AccountConstant.PRINCIPAL_ACCOUNT.equals(principal)) {
                    if (checkAccountPrincipal(uuidRegex)) {
                        return true;
                    }
                } else if (AccountConstant.isAdminPermission(rbacEntity.getApiMessage().getSession())) {
                    if (checkAccountPrincipal(uuidRegex)) {
                        return true;
                    }
                } else if (rbacEntity.getApiMessage().getSession().isUserSession() && AccountConstant.PRINCIPAL_USER.equals(principal)) {
                    if (checkUserPrincipal(uuidRegex)) {
                        return true;
                    }
                } else {
                    throw new CloudRuntimeException(String.format("unknown principal[%s]", principal));
                }
            }

            return false;
        } else {
            return true;
        }
    }

    private boolean additionalApiMatch(String apiName) {
        return rbacEntity.getAdditionalApisToCheck().stream().anyMatch(api -> policyMatcher.match(apiName, api));
    }

    protected void evalDenyStatements(Map<PolicyInventory, List<PolicyStatement>> denyPolices) {
        // action string format is:
        // api-full-name:optional-api-field-list-split-by-comma
        denyPolices.forEach((p, sts)-> {
            sts.forEach(st -> {
                if (!isPrincipalMatched(st.getPrincipals())) {
                    return;
                }

                ActionChecker ac = new ActionChecker();
                ac.p = p;
                ac.st = st;
                ac.check();

                DenyFilterResourceChecker checker = new DenyFilterResourceChecker();
                checker.policyStatement = st;
                checker.check();
            });
        });
    }

    class ActionChecker {
        PolicyStatement st;
        PolicyInventory p;

        void check() {
            if (st.getActions() == null) {
                return;
            }

            st.getActions().forEach(statement -> {
                String[] ss = statement.split(":", 2);
                String apiName = ss[0];
                String apiFields = null;
                if (ss.length > 1) {
                    apiFields = ss[1];
                }

                if (!policyMatcher.match(apiName, rbacEntity.getApiName()) && !additionalApiMatch(apiName)) {
                    // the statement not matching this API and not additional api need to check
                    return;
                }

                // the statement matching this API

                if (apiFields == null) {
                    // no API fields specified, the API is denied by this statement
                    if (logger.isTraceEnabled()) {
                        logger.trace(String.format("[RBAC] policy[name:%s, uuid:%s]'s statement[%s] denies the API:\n%s", p.getName(),
                                p.getUuid(), statement, jsonMessage()));
                    }

                    throw new OperationFailureException(operr("the operation is denied by the policy[name:%s uuid:%s]", p.getName(), p.getUuid()));
                }

                Entity entity = Entity.getEntity(rbacEntity.getApiMessage().getClass());

                for (String fname : apiFields.split(",")) {
                    Field field = entity.getFields().get(fname);
                    try {
                        if (field != null && field.get(rbacEntity.getApiMessage()) != null) {
                            if (logger.isTraceEnabled()) {
                                logger.trace(String.format("[RBAC] policy[name:%s, uuid:%s]'s statement[%s] denies the API:\n%s", p.getName(),
                                        p.getUuid(), statement, jsonMessage()));
                            }
                            throw new OperationFailureException(operr("the operation is denied by the policy[name:%s, uuid:%s], field[%s] is not permitted to set", p.getName(), p.getUuid(), fname));
                        }
                    } catch (IllegalAccessException e) {
                        throw new CloudRuntimeException(e);
                    }
                }
            });
        }
    }

    class DenyFilterResourceChecker {
        PolicyStatement policyStatement;

        void check() {
            Map<String, List<String>> resourceUuidOfAllParamInApiMessage = getResourceUuidOfAllParamInApiMessage();

            if (resourceUuidOfAllParamInApiMessage.isEmpty()) {
                return;
            }

            if (policyStatement.getTargetResources() == null) {
                return;
            }

            policyStatement.getTargetResources().forEach(filter -> {
                String[] ss = filter.split(":", 2);
                String resourceName = ss[0];
                Set<String> deniedResourceUuids = new HashSet<>();

                if (ss.length > 1) {
                    deniedResourceUuids.addAll(Arrays.asList(ss[1].split(",")));
                }

                List<String> resourceUuidsInMessage = resourceUuidOfAllParamInApiMessage.get(resourceName);

                // no matching resource in message skip check
                if (resourceUuidsInMessage == null || resourceUuidsInMessage.isEmpty()) {
                    return;
                }

                // means deny all the resource uuid
                if (deniedResourceUuids.isEmpty()) {
                    throw new OperationFailureException(operr("operation denied"));
                }
                Optional optional = resourceUuidsInMessage.stream().filter(deniedResourceUuids::contains).findAny();
                if (optional.isPresent()) {
                    throw new OperationFailureException(operr("operation to resource [uuid:%s] is denied", optional.get()));
                }
            });
        }
    }

    private Map<String, List<String>> getResourceUuidOfAllParamInApiMessage() {
        Map<String, List<String>> resourceUuidOfAllParamInApiMessage = new HashMap<>();

        APIMessage.getApiParams().get(rbacEntity.getApiMessage().getClass()).forEach(param -> {
            List<String> uuids = new ArrayList<>();
            if (param.param.resourceType() == Object.class) {
                return;
            }

            if (String.class.isAssignableFrom(param.field.getType())) {
                String uuid = null;
                try {
                    uuid = (String) param.field.get(rbacEntity.getApiMessage());
                } catch (IllegalAccessException e) {
                    throw new CloudRuntimeException(e);
                }
                if (uuid != null) {
                    uuids.add(uuid);
                }
            } else if (Collection.class.isAssignableFrom(param.field.getType())) {
                Collection u = null;
                try {
                    u = (Collection<? extends String>) param.field.get(rbacEntity.getApiMessage());
                } catch (IllegalAccessException e) {
                    throw new CloudRuntimeException(e);
                }
                if (u != null) {
                    uuids.addAll(u);
                }
            } else {
                throw new CloudRuntimeException(String.format("not supported field type[%s] for %s#%s", param.field.getType(), rbacEntity.getApiMessage().getClass(), param.field.getName()));
            }

            resourceUuidOfAllParamInApiMessage.putIfAbsent(param.param.resourceType().getSimpleName(), new ArrayList<>());
            resourceUuidOfAllParamInApiMessage.get(param.param.resourceType().getSimpleName()).addAll(uuids);
        });

        return resourceUuidOfAllParamInApiMessage;
    }

    protected boolean checkUserPrincipal(String uuidRegex) {
        return policyMatcher.match(uuidRegex, rbacEntity.getApiMessage().getSession().getUserUuid());
    }

    protected boolean checkAccountPrincipal(String uuidRegex) {
        return policyMatcher.match(uuidRegex, rbacEntity.getApiMessage().getSession().getAccountUuid());
    }

    public Map<String, Boolean> evalAPIPermission(List<Class> classes, SessionInventory session) {
        List<PolicyInventory> policies = RBACManager.getPoliciesBySession(session);
        Map<PolicyInventory, List<PolicyStatement>> denyStatements = RBACManager.collectDenyStatements(policies);
        Map<PolicyInventory, List<PolicyStatement>> allowStatements = RBACManager.collectAllowedStatements(policies);

        Map<String, Boolean> ret = new HashMap<>();
        classes.forEach(clz -> {
            for (List<PolicyStatement> dss : denyStatements.values()) {
                for (PolicyStatement ds : dss) {
                    for (String as : ds.getActions()) {
                        if (evalStatement(as, clz.getName())) {
                            ret.put(clz.getName(), false);
                            return;
                        }
                    }
                }
            }

            for (List<PolicyStatement> ass : allowStatements.values()) {
                for (PolicyStatement as : ass) {
                    for (String a : as.getActions()) {
                        if (evalStatement(a, clz.getName())) {
                            ret.put(clz.getName(), true);
                            return;
                        }
                    }
                }
            }

            ret.put(clz.getName(), false);
        });

        return ret;
    }

}
