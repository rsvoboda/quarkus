package io.quarkus.it.panache.defaultpu;

import jakarta.persistence.Entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;

@Entity
public class Bug7721OtherEntity extends PanacheEntity {
    public String foo;

    public void setFoo(String foo) {
        this.foo = foo.toUpperCase();
    }
}
