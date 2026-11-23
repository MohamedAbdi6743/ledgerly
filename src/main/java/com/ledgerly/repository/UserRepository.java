package com.ledgerly.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ledgerly.entity.AppUser;
import com.ledgerly.entity.Role;

public interface UserRepository extends JpaRepository<AppUser, Long> {

	Optional<AppUser> findByEmail(String email);

	boolean existsByEmail(String email);

	Optional<AppUser> findByIdAndRole(Long id, Role role);

	/** Next number from the member_number_seq sequence (safe when two tellers create members at once). */
	@Query(value = "SELECT nextval('member_number_seq')", nativeQuery = true)
	long nextMemberNumber();

	/** Members whose name, email or member number contains the text (case-insensitive). */
	@Query("""
			SELECT u FROM AppUser u
			WHERE u.role = com.ledgerly.entity.Role.MEMBER
			  AND (LOWER(u.fullName) LIKE LOWER(CONCAT('%', :text, '%'))
			    OR LOWER(u.email) LIKE LOWER(CONCAT('%', :text, '%'))
			    OR LOWER(u.memberNumber) LIKE LOWER(CONCAT('%', :text, '%')))
			ORDER BY u.fullName
			""")
	List<AppUser> searchMembers(@Param("text") String text, Pageable limit);

}
