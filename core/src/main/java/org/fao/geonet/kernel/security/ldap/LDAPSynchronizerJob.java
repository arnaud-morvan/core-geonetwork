//=============================================================================
//===	Copyright (C) 2001-2012 Food and Agriculture Organization of the
//===	United Nations (FAO-UN), United Nations World Food Programme (WFP)
//===	and United Nations Environment Programme (UNEP)
//===
//===	This program is free software; you can redistribute it and/or modify
//===	it under the terms of the GNU General Public License as published by
//===	the Free Software Foundation; either version 2 of the License, or (at
//===	your option) any later version.
//===
//===	This program is distributed in the hope that it will be useful, but
//===	WITHOUT ANY WARRANTY; without even the implied warranty of
//===	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
//===	General Public License for more details.
//===
//===	You should have received a copy of the GNU General Public License
//===	along with this program; if not, write to the Free Software
//===	Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
//===
//===	Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
//===	Rome - Italy. email: geonetwork@osgeo.org
//==============================================================================
package org.fao.geonet.kernel.security.ldap;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import org.fao.geonet.domain.UserGroupId_;
import org.fao.geonet.utils.Log;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.domain.Group;
import org.fao.geonet.domain.User;
import org.fao.geonet.repository.GroupRepository;
import org.fao.geonet.repository.UserGroupRepository;
import org.fao.geonet.repository.UserRepository;
import org.fao.geonet.repository.specification.UserSpecs;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.domain.Specifications;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.naming.directory.SearchResult;
import javax.transaction.TransactionManager;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LDAPSynchronizerJob extends QuartzJobBean {
    
    private ApplicationContext applicationContext;
    
    private DefaultSpringSecurityContextSource contextSource;
    
    @Override
    protected void executeInternal(JobExecutionContext jobExecContext)
            throws JobExecutionException {
        try {
            if (Log.isDebugEnabled(Geonet.LDAP)) {
                Log.debug(Geonet.LDAP, "LDAPSynchronizerJob starting ...");
            }
            
            // Retrieve application context. A defautl SpringBeanJobFactory
            // will not provide the application context to the job. Use
            // AutowiringSpringBeanJobFactory.
            applicationContext = (ApplicationContext) jobExecContext
                    .getJobDetail().getJobDataMap().get("applicationContext");
            
            
            if (applicationContext == null) {
                Log.error(
                        Geonet.LDAP,
                        "  Application context is null. Be sure to configure SchedulerFactoryBean job factory property with AutowiringSpringBeanJobFactory.");
            }
            
            // Get LDAP information defining which users to sync
            final JobDataMap jdm = jobExecContext.getJobDetail()
                    .getJobDataMap();
            contextSource = (DefaultSpringSecurityContextSource) jdm
                    .get("contextSource");
            
            String ldapUserSearchFilter = (String) jdm
                    .get("ldapUserSearchFilter");
            String ldapUserSearchBase = (String) jdm.get("ldapUserSearchBase");
            String ldapUserSearchAttribute = (String) jdm
                    .get("ldapUserSearchAttribute");
            
            DirContext dc = contextSource.getReadOnlyContext();
            
            // start transaction
            final TransactionManager transactionManager = applicationContext.getBean(TransactionManager.class);
            transactionManager.begin();

            try {
                // Users
                synchronizeUser(applicationContext, ldapUserSearchFilter, ldapUserSearchBase,
                        ldapUserSearchAttribute, dc);
                
                // And optionaly groups
                String createNonExistingLdapGroup = (String) jdm
                        .get("createNonExistingLdapGroup");
                
                if ("true".equals(createNonExistingLdapGroup)) {
                    String ldapGroupSearchFilter = (String) jdm
                            .get("ldapGroupSearchFilter");
                    String ldapGroupSearchBase = (String) jdm
                            .get("ldapGroupSearchBase");
                    String ldapGroupSearchAttribute = (String) jdm
                            .get("ldapGroupSearchAttribute");
                    String ldapGroupSearchPattern = (String) jdm
                            .get("ldapGroupSearchPattern");
                    
                    synchronizeGroup(applicationContext, ldapGroupSearchFilter,
                            ldapGroupSearchBase, ldapGroupSearchAttribute,
                            ldapGroupSearchPattern, dc);
                }
            } catch (NamingException e1) {
                try {
                    transactionManager.rollback();
                } catch (Exception e2) {
                    e2.printStackTrace();
                    Log.error(Geonet.LDAP, "Error rolling back transaction", e2);
                }
                e1.printStackTrace();
            } catch (Exception e) {
                try {
                    transactionManager.rollback();
                } catch (Exception e2) {
                    e.printStackTrace();
                    Log.error(Geonet.LDAP, "Error rolling back transaction", e2);
                }
                Log.error(
                        Geonet.LDAP,
                        "Unexpected error while synchronizing LDAP user in database",
                        e);
            } finally {
                try {
                    dc.close();
                } catch (NamingException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                    try {
                        transactionManager.commit();
                    } catch (Exception e) {
                        e.printStackTrace();
                        Log.error(Geonet.LDAP, "Error committing transaction" , e);
                    }
            }
        } catch (Exception e) {
            Log.error(
                    Geonet.LDAP,
                    "Unexpected error while synchronizing LDAP user in database",
                    e);
            e.printStackTrace();
        }
        
        if (Log.isDebugEnabled(Geonet.LDAP)) {
            Log.debug(Geonet.LDAP, "LDAPSynchronizerJob done.");
        }
    }
    
    
    private void synchronizeUser(ApplicationContext applicationContext, String ldapUserSearchFilter,
                                 String ldapUserSearchBase, String ldapUserSearchAttribute,
                                 DirContext dc) throws NamingException, SQLException {
        // Do something for LDAP users ? Currently user is updated on log
        // in only.
        NamingEnumeration<?> userList = dc.search(ldapUserSearchBase,
                ldapUserSearchFilter, null);
        
        // Build a list of LDAP users
        Set<String> usernames = new HashSet<String>();
        while (userList.hasMore()) {
            SearchResult sr = (SearchResult) userList.next();
            final String username = sr.getAttributes().get(ldapUserSearchAttribute)
                    .get().toString();
            usernames.add(username);
        }

        // Remove LDAP user available in db and not in LDAP if not linked to
        // metadata
        final UserRepository userRepository = applicationContext.getBean(UserRepository.class);
        final UserGroupRepository userGroupRepository = applicationContext.getBean(UserGroupRepository.class);
        final Specifications<User> spec = Specifications.where(
                UserSpecs.hasAuthType(LDAPConstants.LDAP_FLAG)
        ).and(
                Specifications.not(UserSpecs.userIsNameNotOneOf(usernames))
        );

        final List<User> usersFound = userRepository.findAll(spec);
        Collection<Integer> userIds = Collections2.transform(usersFound, new Function<User, Integer>() {
            @Nullable
            @Override
            public Integer apply(@Nonnull User input) {
                return input.getId();
            }
        });
        userGroupRepository.deleteAllByIdAttribute(UserGroupId_.userId, userIds);
        userRepository.deleteInBatch(usersFound);
    }
    
    
    private void synchronizeGroup(ApplicationContext applicationContext, String ldapGroupSearchFilter,
                                  String ldapGroupSearchBase, String ldapGroupSearchAttribute,
                                  String ldapGroupSearchPattern, DirContext dc) throws  NamingException, SQLException {
        
        NamingEnumeration<?> groupList = dc.search(ldapGroupSearchBase,
                ldapGroupSearchFilter, null);
        Pattern ldapGroupSearchPatternCompiled = null;
        if (ldapGroupSearchPattern != null && !"".equals(ldapGroupSearchPattern)) {
            ldapGroupSearchPatternCompiled = Pattern.compile(ldapGroupSearchPattern);
        }
        
        while (groupList.hasMore()) {
            SearchResult sr = (SearchResult) groupList.next();
            
            // TODO : should we retrieve LDAP group id and do an update of group
            // name
            // This will require to store in local db the remote id
            String groupName = (String) sr.getAttributes()
                    .get(ldapGroupSearchAttribute).get();
            
            if (ldapGroupSearchPatternCompiled != null && !"".equals(ldapGroupSearchPattern)) {
                Matcher m = ldapGroupSearchPatternCompiled.matcher(groupName);
                boolean b = m.matches();
                if (b) {
                    groupName = m.group(1);
                }
            }
            
            GroupRepository groupRepo = this.applicationContext.getBean(GroupRepository.class);
            Group group = groupRepo.findByName(groupName);
            
            if (group == null) {
                group = groupRepo.save(new Group().setName(groupName));
            } else {
                // Update something ?
                // Group description is only defined in catalog, not in LDAP for the time
                // being
            }
        }
    }
    
    public DefaultSpringSecurityContextSource getContextSource() {
        return contextSource;
    }
    
    public void setContextSource(
            DefaultSpringSecurityContextSource contextSource) {
        this.contextSource = contextSource;
    }
}
