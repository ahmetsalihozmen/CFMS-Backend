package tr.edu.yildiz.cfms.business.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tr.edu.yildiz.cfms.entities.concretes.hibernate.User;

public interface UserRepository extends JpaRepository<User, String> {
}
